package com.localis.index;

import com.localis.llm.LlmClient;
import com.localis.llm.LlmException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Indexa el repositorio de forma incremental: solo reprocesa los ficheros cuyo contenido cambio. */
public final class Indexer {

    private static final Logger LOG = LoggerFactory.getLogger(Indexer.class);
    private static final int EMBED_BATCH = 16;

    public enum State { IDLE, RUNNING, DONE, FAILED }

    /**
     * Estado de la indexacion. filesSkipped = sin cambios desde la ultima vez; filesBinary = con extension de texto
     * pero contenido binario; omitted = lo que el escaneo dejo fuera y por que (ver RepositoryScanner.Omitted), para
     * que la cobertura del indice sea visible.
     */
    public record Status(State state, int filesTotal, int filesDone, int filesSkipped, int filesFailed,
            int filesBinary, boolean embeddingsAvailable, String message, RepositoryScanner.Omitted omitted) {

        static Status of(State state, String message) {
            return new Status(state, 0, 0, 0, 0, 0, true, message, RepositoryScanner.Omitted.none());
        }

        Status with(State state, String message) {
            return new Status(state, filesTotal, filesDone, filesSkipped, filesFailed, filesBinary,
                    embeddingsAvailable, message, omitted);
        }
    }

    private final RepositoryScanner scanner;
    private final IndexStore store;
    private final LlmClient llm;
    private final Chunker chunker = new Chunker();
    private volatile Status status = Status.of(State.IDLE, "");
    private Thread worker;
    private volatile Runnable onFinished = () -> { };

    public Indexer(RepositoryScanner scanner, IndexStore store, LlmClient llm) {
        this.scanner = scanner;
        this.store = store;
        this.llm = llm;
    }

    /** Se ejecuta al terminar una indexacion con exito (p. ej. para precalentar el buscador). */
    public void onFinished(Runnable r) {
        this.onFinished = r;
    }

    public Status status() {
        return status;
    }

    /** Lanza la indexacion en segundo plano. Devuelve false si ya hay una en curso. */
    public synchronized boolean startAsync() {
        if (status.state() == State.RUNNING) {
            return false;
        }
        status = Status.of(State.RUNNING, "Escaneando repositorio");
        worker = Thread.ofVirtual().name("localis-indexer").start(() -> {
            try {
                run();
                onFinished.run();
            } catch (Exception e) {
                LOG.error("Fallo la indexacion", e);
                status = status.with(State.FAILED, String.valueOf(e.getMessage()));
            }
        });
        return true;
    }

    /** Solo para tests y uso por linea de comandos. */
    public void awaitCompletion() throws InterruptedException {
        Thread t;
        synchronized (this) {
            t = worker;
        }
        if (t != null) {
            t.join();
        }
    }

    /** Indexa de forma sincrona (usado por el hilo de fondo). */
    void run() throws IOException {
        RepositoryScanner.Scan scan = scanner.scanAll();
        List<RepositoryScanner.SourceFile> files = scan.files();
        RepositoryScanner.Omitted omitted = scan.omitted();
        int done = 0;
        int skipped = 0;
        int failed = 0;
        int binary = 0;
        boolean embeddingsOk = true;
        status = new Status(State.RUNNING, files.size(), 0, 0, 0, 0, true, "Indexando", omitted);

        Set<String> present = new HashSet<>();
        for (RepositoryScanner.SourceFile sf : files) {
            present.add(sf.relativePath());
            try {
                byte[] bytes = scanner.readBytes(sf.relativePath());
                String hash = RepositoryScanner.sha256(bytes);
                var known = store.file(sf.relativePath());
                if (known.isPresent() && known.get().hash().equals(hash) && (known.get().embedded() || !embeddingsOk)) {
                    skipped++;
                } else {
                    String text = RepositoryScanner.decode(bytes);
                    if (text == null) {
                        binary++;
                    } else {
                        List<CodeChunk> chunks = chunker.chunk(sf.relativePath(), text);
                        boolean embedded = false;
                        if (embeddingsOk && !chunks.isEmpty()) {
                            try {
                                chunks = embedAll(chunks);
                                embedded = true;
                            } catch (LlmException e) {
                                embeddingsOk = false;
                                LOG.warn("Embeddings no disponibles, se indexa solo por palabras clave: {}",
                                        e.getMessage());
                            }
                        }
                        store.replaceFile(sf.relativePath(), hash, Chunker.splitLines(text).size(), embedded, chunks);
                    }
                }
            } catch (IOException | RuntimeException e) {
                failed++;
                LOG.warn("No se pudo indexar {}: {}", sf.relativePath(), e.getMessage());
            }
            done++;
            status = new Status(State.RUNNING, files.size(), done, skipped, failed, binary, embeddingsOk, "Indexando",
                    omitted);
        }

        for (String old : new ArrayList<>(store.filePaths())) {
            if (!present.contains(old)) {
                store.deleteFile(old);
            }
        }
        status = new Status(State.DONE, files.size(), done, skipped, failed, binary, embeddingsOk,
                embeddingsOk ? "Indice actualizado" : "Indice actualizado sin embeddings (solo busqueda por palabras)",
                omitted);
    }

    private List<CodeChunk> embedAll(List<CodeChunk> chunks) {
        List<CodeChunk> out = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i += EMBED_BATCH) {
            List<CodeChunk> batch = chunks.subList(i, Math.min(i + EMBED_BATCH, chunks.size()));
            List<float[]> vectors = llm.embed(batch.stream().map(CodeChunk::embeddingInput).toList());
            for (int j = 0; j < batch.size(); j++) {
                out.add(batch.get(j).withEmbedding(vectors.get(j)));
            }
        }
        return out;
    }
}
