package com.localis.api;

import com.localis.llm.LlmStats;
import com.localis.rag.Progress;
import com.localis.workspace.Workspace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Ejecuta los analisis largos (explicar / diagnosticar) fuera de la peticion HTTP. Solo hay uno activo: los modelos
 * locales procesan de uno en uno, asi que lanzar mas solo formaria cola y bloquearia las siguientes preguntas. Un
 * trabajo se puede cancelar (interrumpiendo la llamada al modelo) y se cancela solo si nadie pregunta por el.
 */
final class JobManager {

    enum State { RUNNING, DONE, FAILED, CANCELLED }

    record Failure(int status, String message) {}

    static final class Job {
        final String id = UUID.randomUUID().toString();
        final long startedAt = System.currentTimeMillis();
        volatile State state = State.RUNNING;
        volatile String progress = "Iniciando";
        volatile Object result;
        volatile Map<String, Object> stats = Map.of();
        volatile Failure failure;
        volatile long finishedAt;
        volatile long lastPolled = System.currentTimeMillis();
        volatile Thread thread;
    }

    private static final int KEEP_FINISHED = 10;

    private final Function<Exception, Failure> errors;
    private final long abandonMillis;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "localis-job-watchdog");
        t.setDaemon(true);
        return t;
    });
    private Job active;

    JobManager(Function<Exception, Failure> errors, long abandonMillis) {
        this.errors = errors;
        this.abandonMillis = abandonMillis;
        long period = Math.max(50, Math.min(5000, abandonMillis / 4));
        watchdog.scheduleWithFixedDelay(this::cancelAbandoned, period, period, TimeUnit.MILLISECONDS);
    }

    synchronized boolean isBusy() {
        return active != null && active.state == State.RUNNING;
    }

    synchronized Job start(Callable<Object> work) {
        if (isBusy()) {
            throw new Workspace.BusyException("Ya hay un analisis en curso: espere a que termine o cancelelo");
        }
        Job job = new Job();
        active = job;
        jobs.put(job.id, job);
        trimFinished();
        job.thread = Thread.ofPlatform().name("localis-job-" + job.id.substring(0, 8)).daemon(true)
                .start(() -> run(job, work));
        return job;
    }

    private void run(Job job, Callable<Object> work) {
        try (AutoCloseable ignored = Progress.bind(m -> job.progress = m);
                AutoCloseable stats = LlmStats.collect()) {
            Object r = work.call();
            job.stats = LlmStats.summary();
            finish(job, State.DONE, r, null);
        } catch (CancellationException e) {
            finish(job, State.CANCELLED, null, null);
        } catch (Exception e) {
            if (job.state == State.CANCELLED || Thread.currentThread().isInterrupted()) {
                finish(job, State.CANCELLED, null, null);
            } else {
                finish(job, State.FAILED, null, errors.apply(e));
            }
        }
    }

    private synchronized void finish(Job job, State state, Object result, Failure failure) {
        if (job.state != State.RUNNING && job.state != State.CANCELLED) {
            return;
        }
        // Un trabajo cancelado a mano sigue siendo CANCELLED aunque el hilo termine con otro resultado.
        job.state = job.state == State.CANCELLED ? State.CANCELLED : state;
        job.result = job.state == State.DONE ? result : null;
        job.failure = failure;
        job.finishedAt = System.currentTimeMillis();
    }

    synchronized Job get(String id) {
        Job j = id == null ? null : jobs.get(id);
        if (j != null) {
            j.lastPolled = System.currentTimeMillis();
        }
        return j;
    }

    synchronized Job cancel(String id) {
        Job j = jobs.get(id);
        if (j != null && j.state == State.RUNNING) {
            j.state = State.CANCELLED;
            j.finishedAt = System.currentTimeMillis();
            j.progress = "Cancelado";
            if (j.thread != null) {
                j.thread.interrupt();
            }
        }
        return j;
    }

    private synchronized void cancelAbandoned() {
        Job j = active;
        if (j != null && j.state == State.RUNNING && System.currentTimeMillis() - j.lastPolled > abandonMillis) {
            cancel(j.id);
        }
    }

    private void trimFinished() {
        List<String> finished = new ArrayList<>();
        jobs.forEach((id, j) -> {
            if (j.state != State.RUNNING) {
                finished.add(id);
            }
        });
        for (int i = 0; i < finished.size() - KEEP_FINISHED; i++) {
            jobs.remove(finished.get(i));
        }
    }

    synchronized void shutdown() {
        if (active != null) {
            cancel(active.id);
        }
        watchdog.shutdownNow();
    }

    Map<String, Object> snapshot(Job j) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", j.id);
        out.put("state", j.state.name());
        out.put("progress", j.progress);
        long end = j.finishedAt > 0 ? j.finishedAt : System.currentTimeMillis();
        out.put("elapsedSeconds", (end - j.startedAt) / 1000);
        if (j.state == State.DONE) {
            out.put("result", j.result);
            out.put("stats", j.stats);
        }
        if (j.state == State.FAILED && j.failure != null) {
            out.put("error", j.failure.message());
            out.put("status", j.failure.status());
        }
        return out;
    }
}
