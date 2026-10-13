package com.localis.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localis.config.AppConfig;
import com.localis.index.IndexStore;
import com.localis.index.Indexer;
import com.localis.index.RepositoryScanner;
import com.localis.llm.LlmClient;
import com.localis.rag.AnswerEngine;
import com.localis.rag.AnswerResult;
import com.localis.rag.CitationVerifier;
import com.localis.rag.DiagnoseService;
import com.localis.rag.ExplainService;
import com.localis.rag.SnippetExtractor;
import com.localis.search.Retriever;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Repositorio activo y todo lo que depende de el (indice, buscador, servicios de IA). Permite cambiar de repositorio
 * en caliente; cada repositorio tiene su propio indice en data.dir/repos/&lt;id&gt; y la ultima eleccion se recuerda.
 */
public final class Workspace implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Workspace.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SETTINGS_FILE = "workspace.json";

    private record State(Path repo, IndexStore store, RepositoryScanner scanner, Indexer indexer,
            ExplainService explain, DiagnoseService diagnose) {}

    /** No se puede cambiar de repositorio mientras se indexa el actual. */
    public static final class BusyException extends RuntimeException {
        public BusyException(String message) {
            super(message);
        }
    }

    private final AppConfig cfg;
    private final LlmClient llm;
    private volatile State state;

    public Workspace(AppConfig cfg, LlmClient llm) {
        this.cfg = cfg;
        this.llm = llm;
        restore();
    }

    private void restore() {
        Path saved = readSaved();
        for (Path candidate : new Path[] {saved, cfg.repoPath()}) {
            if (candidate == null) {
                continue;
            }
            try {
                open(candidate);
                return;
            } catch (IOException | RuntimeException e) {
                LOG.warn("No se pudo abrir el repositorio {}: {}", candidate, e.getMessage());
            }
        }
    }

    public Optional<Path> repo() {
        State s = state;
        return s == null ? Optional.empty() : Optional.of(s.repo());
    }

    public IndexStore.Stats stats() {
        State s = state;
        return s == null ? new IndexStore.Stats(0, 0, 0) : s.store().stats();
    }

    /** null si no hay repositorio configurado. */
    public Indexer indexer() {
        State s = state;
        return s == null ? null : s.indexer();
    }

    public RepositoryScanner scanner() {
        return require().scanner();
    }

    public ExplainService explain() {
        return require().explain();
    }

    public DiagnoseService diagnose() {
        return require().diagnose();
    }

    /** Funciones completas implicadas en la respuesta (por citas verificadas o, si no hay, por lineas mencionadas). */
    public List<SnippetExtractor.Snippet> snippets(AnswerResult r) {
        State s = state;
        if (s == null) {
            return List.of();
        }
        List<CitationVerifier.Citation> cites = r.citations().stream().anyMatch(CitationVerifier.Citation::verified)
                ? r.citations() : SnippetExtractor.inferFromText(r.answer(), r.sources());
        return SnippetExtractor.extract(cites, s.store(), s.scanner());
    }

    private State require() {
        State s = state;
        if (s == null) {
            throw new IllegalArgumentException("No hay repositorio configurado: elija una carpeta primero");
        }
        return s;
    }

    /** Cambia el repositorio activo. Falla si la ruta no es una carpeta o si hay una indexacion en curso. */
    public synchronized void open(Path requested) throws IOException {
        Path repo = requested.toAbsolutePath().normalize();
        if (!Files.isDirectory(repo) || !Files.isReadable(repo)) {
            throw new IllegalArgumentException("No es una carpeta accesible: " + repo);
        }
        State old = state;
        if (old != null && old.repo().equals(repo)) {
            return;
        }
        if (old != null && old.indexer().status().state() == Indexer.State.RUNNING) {
            throw new BusyException("Hay una indexacion en curso; espere a que termine");
        }
        Path dir = cfg.dataDir().resolve("repos").resolve(id(repo));
        Files.createDirectories(dir);
        IndexStore store = new IndexStore(dir.resolve("index.db"));
        var scanner = new RepositoryScanner(repo);
        var retriever = new Retriever(store, llm);
        var engine = new AnswerEngine(llm, new CitationVerifier(store::filePaths,
                p -> store.file(p).map(IndexStore.FileInfo::lines).orElse(0)));
        state = new State(repo, store, scanner, new Indexer(scanner, store, llm),
                new ExplainService(llm, engine, retriever, store, scanner, cfg.retrievalTopK(), cfg.maxContextChars()),
                new DiagnoseService(llm, engine, retriever, cfg.retrievalTopK(), cfg.maxContextChars()));
        if (old != null) {
            old.store().close();
        }
        save(repo);
    }

    @Override
    public synchronized void close() {
        State s = state;
        if (s != null) {
            s.store().close();
        }
    }

    private static String id(Path repo) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(repo.toString().toLowerCase()
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path readSaved() {
        Path f = cfg.dataDir().resolve(SETTINGS_FILE);
        try {
            if (Files.isRegularFile(f)) {
                String p = JSON.readTree(f.toFile()).path("repoPath").asText("");
                return p.isBlank() ? null : Path.of(p);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Ajustes de repositorio ilegibles ({}): {}", f, e.getMessage());
        }
        return null;
    }

    private void save(Path repo) {
        try {
            Files.createDirectories(cfg.dataDir());
            Path tmp = cfg.dataDir().resolve(SETTINGS_FILE + ".tmp");
            JSON.writeValue(tmp.toFile(), Map.of("repoPath", repo.toString()));
            Files.move(tmp, cfg.dataDir().resolve(SETTINGS_FILE), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.warn("No se pudo recordar el repositorio elegido: {}", e.getMessage());
        }
    }
}
