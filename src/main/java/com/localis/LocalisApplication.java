package com.localis;

import com.localis.api.ApiServer;
import com.localis.config.AppConfig;
import com.localis.docs.DocumentationService;
import com.localis.license.LicenseValidator;
import com.localis.license.MachineFingerprint;
import com.localis.llm.OllamaClient;
import com.localis.net.NetworkGuard;
import com.localis.workspace.Workspace;
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
        var workspace = new Workspace(cfg, llm);
        if (workspace.repo().isEmpty()) {
            LOG.warn("Ningun repositorio configurado: elijalo desde la interfaz web (boton Configurar)");
        }
        var docs = new DocumentationService(cfg.templatesDir(), cfg.companyName(), cfg.logoPath());

        var server = new ApiServer(new ApiServer.Deps(cfg, llm, workspace, docs, moduleAllowed,
                () -> licenseInfo));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            workspace.close();
        }));
        server.start();
    }

    private static void fail(String message) {
        LOG.error(message);
        System.exit(2);
    }
}
