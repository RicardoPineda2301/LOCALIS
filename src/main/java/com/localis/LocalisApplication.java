package com.localis;

import com.localis.api.ApiServer;
import com.localis.config.AppConfig;
import com.localis.docs.DocumentationService;
import com.localis.index.IndexStore;
import com.localis.index.Indexer;
import com.localis.index.RepositoryScanner;
import com.localis.license.LicenseValidator;
import com.localis.license.MachineFingerprint;
import com.localis.llm.OllamaClient;
import com.localis.net.NetworkGuard;
import com.localis.rag.AnswerEngine;
import com.localis.rag.CitationVerifier;
import com.localis.rag.DiagnoseService;
import com.localis.rag.ExplainService;
import com.localis.search.Retriever;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocalisApplication {

    private static final Logger LOG = LoggerFactory.getLogger(LocalisApplication.class);

    private LocalisApplication() {}

    public static void main(String[] args) throws Exception {
        Path configFile = Path.of(args.length > 0 ? args[0] : System.getProperty("localis.config",
                "localis.properties"));
        AppConfig cfg = AppConfig.load(configFile);

        Predicate<String> moduleAllowed = m -> true;
        Map<String, Object> licenseInfo = new LinkedHashMap<>();
        licenseInfo.put("enforced", cfg.licenseEnforce());
        if (cfg.licenseEnforce()) {
            var key = LicenseValidator.loadPublicKey(cfg.licensePublicKey());
            if (key.isEmpty()) {
                fail("Licencia obligatoria pero no hay clave publica (license.publicKey o license-public.pem)");
            }
            var validator = new LicenseValidator(key.get(), MachineFingerprint.current(), Clock.systemDefaultZone());
            var result = validator.validate(cfg.licenseFile());
            if (!result.valid()) {
                fail("Licencia no valida: " + result.reason());
            }
            licenseInfo.put("customer", result.license().customer());
            licenseInfo.put("expiresAt", result.license().expiresAt() == null ? "perpetual"
                    : result.license().expiresAt());
            moduleAllowed = result::moduleEnabled;
        } else {
            LOG.warn("Licencia NO exigida (license.enforce=false): solo para desarrollo");
        }

        var guard = new NetworkGuard(cfg.allowedHosts());
        var llm = new OllamaClient(cfg, guard);
        var store = new IndexStore(cfg.dataDir().resolve("index.db"));
        var retriever = new Retriever(store, llm);
        var verifier = new CitationVerifier(store::filePaths,
                p -> store.file(p).map(IndexStore.FileInfo::lines).orElse(0));
        var engine = new AnswerEngine(llm, verifier);

        RepositoryScanner scanner = cfg.repoPath() == null ? null : new RepositoryScanner(cfg.repoPath());
        Indexer indexer = scanner == null ? null : new Indexer(scanner, store, llm);
        if (scanner == null) {
            LOG.warn("repo.path no configurado: indexacion y preguntas sobre codigo no disponibles");
        }
        var explain = new ExplainService(llm, engine, retriever, store, scanner, cfg.retrievalTopK(),
                cfg.maxContextChars());
        var diagnose = new DiagnoseService(llm, engine, retriever, cfg.retrievalTopK(), cfg.maxContextChars());
        var docs = new DocumentationService(cfg.templatesDir(), cfg.companyName(), cfg.logoPath());

        var server = new ApiServer(new ApiServer.Deps(cfg, llm, store, indexer, explain, diagnose, docs,
                moduleAllowed, () -> licenseInfo));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            store.close();
        }));
        server.start();
    }

    private static void fail(String message) {
        LOG.error(message);
        System.exit(2);
    }
}
