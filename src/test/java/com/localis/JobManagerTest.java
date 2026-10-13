package com.localis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localis.rag.Progress;
import com.localis.workspace.Workspace;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** JobManager es interno del paquete api: se prueba por reflexion para no ampliar su visibilidad. */
class JobManagerTest {

    private Object manager;
    private Class<?> managerType;
    private Class<?> jobType;
    private Class<?> failureType;

    private void create(long abandonMillis) throws Exception {
        managerType = Class.forName("com.localis.api.JobManager");
        jobType = Class.forName("com.localis.api.JobManager$Job");
        failureType = Class.forName("com.localis.api.JobManager$Failure");
        Constructor<?> fc = failureType.getDeclaredConstructor(int.class, String.class);
        fc.setAccessible(true);
        Function<Exception, Object> errors = e -> {
            try {
                return fc.newInstance(500, "fallo: " + e.getMessage());
            } catch (ReflectiveOperationException x) {
                throw new IllegalStateException(x);
            }
        };
        Constructor<?> c = managerType.getDeclaredConstructor(Function.class, long.class);
        c.setAccessible(true);
        manager = c.newInstance(errors, abandonMillis);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (manager != null) {
            call("shutdown");
        }
    }

    private Object call(String name, Object... args) throws Exception {
        for (Method m : managerType.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                m.setAccessible(true);
                try {
                    return m.invoke(manager, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    if (e.getCause() instanceof Exception ex) {
                        throw ex;
                    }
                    throw e;
                }
            }
        }
        throw new NoSuchMethodException(name);
    }

    private Object field(Object job, String name) throws Exception {
        var f = jobType.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(job);
    }

    private String state(Object job) throws Exception {
        return String.valueOf(field(job, "state"));
    }

    private void waitFor(Object job, String expected) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end && !state(job).equals(expected)) {
            Thread.sleep(10);
        }
        assertEquals(expected, state(job));
    }

    private Object start(Callable<Object> work) throws Exception {
        return call("start", work);
    }

    @Test
    void runsTheWorkAndReportsProgressAndResult() throws Exception {
        create(60_000);
        Object job = start(() -> {
            Progress.report("paso uno");
            return "listo";
        });
        waitFor(job, "DONE");
        assertEquals("listo", field(job, "result"));
        assertEquals("paso uno", field(job, "progress"));
    }

    @Test
    void onlyOneJobRunsAtATimeAndTheNextCanStartAfterwards() throws Exception {
        create(60_000);
        CountDownLatch release = new CountDownLatch(1);
        Object first = start(() -> {
            release.await();
            return "a";
        });
        Exception busy = assertThrows(Workspace.BusyException.class, () -> start(() -> "b"));
        assertTrue(busy.getMessage().contains("en curso"));
        assertEquals(true, call("isBusy"));

        release.countDown();
        waitFor(first, "DONE");
        Object second = start(() -> "b");
        waitFor(second, "DONE");
    }

    @Test
    void cancelInterruptsABlockedCallSoTheModelRequestIsAborted() throws Exception {
        create(60_000);
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(1);
        Object job = start(() -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrumpido");
            }
            return "no deberia llegar";
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        call("cancel", field(job, "id"));

        waitFor(job, "CANCELLED");
        long end = System.currentTimeMillis() + 3000;
        while (!interrupted.get() && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }
        assertTrue(interrupted.get(), "la llamada bloqueada debe recibir la interrupcion");
        assertEquals(false, call("isBusy"), "tras cancelar se puede lanzar otro analisis");
    }

    @Test
    void failuresAreMappedAndDoNotLeakAsDone() throws Exception {
        create(60_000);
        Object job = start(() -> {
            throw new IllegalStateException("kaput");
        });
        waitFor(job, "FAILED");
        assertNotNull(field(job, "failure"));
        assertTrue(String.valueOf(field(job, "failure")).contains("fallo: kaput"));
    }

    @Test
    void aJobNobodyPollsIsCancelledButAPolledOneSurvives() throws Exception {
        create(300);
        CountDownLatch never = new CountDownLatch(1);
        Object abandoned = start(() -> {
            never.await();
            return "x";
        });
        waitFor(abandoned, "CANCELLED");

        Object watched = start(() -> {
            never.await();
            return "y";
        });
        long end = System.currentTimeMillis() + 1200;
        while (System.currentTimeMillis() < end) {
            call("get", field(watched, "id"));
            Thread.sleep(50);
        }
        assertEquals("RUNNING", state(watched));
    }

    @Test
    void unknownJobsReturnNull() throws Exception {
        create(60_000);
        assertEquals(null, call("get", "no-existe"));
        assertEquals(null, call("cancel", "no-existe"));
    }
}
