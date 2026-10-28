package com.localis.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/** Configuracion de LOCALIS. Todo tiene un valor por defecto seguro: solo loopback, sin internet. */
public record AppConfig(
        String serverHost,
        int serverPort,
        String apiToken,
        String ollamaBaseUrl,
        String chatModel,
        String embedModel,
        int numCtx,
        int llmTimeoutSeconds,
        String keepAlive,
        int maxTokens,
        List<String> allowedHosts,
        Path repoPath,
        Path dataDir,
        Path templatesDir,
        String companyName,
        Path logoPath,
        boolean licenseEnforce,
        Path licenseFile,
        Path licensePublicKey,
        int retrievalTopK,
        int maxContextChars,
        List<String> indexIgnoreDirs) {

    public static AppConfig defaults() {
        return from(new Properties());
    }

    public static AppConfig load(Path file) throws IOException {
        Properties p = new Properties();
        if (file != null && Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return from(p);
    }

    /** Caracteres de codigo por token, estimacion conservadora para codigo con numeros de linea. */
    private static final int CHARS_PER_TOKEN = 3;
    /** Tokens reservados para el prompt de sistema, la pregunta y las reglas de idioma. */
    private static final int PROMPT_RESERVE_TOKENS = 500;
    private static final int RESPONSE_MARGIN_TOKENS = 500;

    /**
     * Presupuesto de contexto (en caracteres) que cabe en la ventana del modelo una vez descontados la respuesta y el
     * prompt. Se usa cuando retrieval.maxContextChars no esta definido, para que subir ollama.numCtx aproveche
     * automaticamente la ventana extra.
     */
    public static int defaultMaxContextChars(int numCtx, int maxTokens) {
        int available = numCtx - maxTokens - RESPONSE_MARGIN_TOKENS - PROMPT_RESERVE_TOKENS;
        return Math.max(4000, available * CHARS_PER_TOKEN);
    }

    public static AppConfig from(Properties p) {
        int numCtx = Integer.parseInt(str(p, "ollama.numCtx", "8192"));
        int maxTokens = Integer.parseInt(str(p, "ollama.maxTokens", "1500"));
        String maxContextChars = str(p, "retrieval.maxContextChars", "");
        return new AppConfig(
                str(p, "server.host", "127.0.0.1"),
                Integer.parseInt(str(p, "server.port", "8080")),
                str(p, "server.apiToken", ""),
                str(p, "ollama.baseUrl", "http://localhost:11434"),
                str(p, "ollama.chatModel", "mistralai/Devstral-Small-2-24B-Instruct-2512"),
                str(p, "ollama.embedModel", "nomic-embed-text"),
                numCtx,
                Integer.parseInt(str(p, "ollama.timeoutSeconds", "600")),
                str(p, "ollama.keepAlive", "30m"),
                maxTokens,
                list(str(p, "network.allowedHosts", "localhost,127.0.0.1,::1")),
                path(str(p, "repo.path", "")),
                Path.of(str(p, "data.dir", "./data")),
                Path.of(str(p, "templates.dir", "./templates")),
                str(p, "branding.company", ""),
                path(str(p, "branding.logo", "")),
                Boolean.parseBoolean(str(p, "license.enforce", "false")),
                Path.of(str(p, "license.file", "./license.json")),
                path(str(p, "license.publicKey", "")),
                Integer.parseInt(str(p, "retrieval.topK", "8")),
                maxContextChars.isBlank() || maxContextChars.equals("0")
                        ? defaultMaxContextChars(numCtx, maxTokens) : Integer.parseInt(maxContextChars),
                list(str(p, "index.ignoreDirs", "")));
    }

    private static String str(Properties p, String key, String def) {
        String env = System.getenv("LOCALIS_" + key.toUpperCase().replace('.', '_'));
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return p.getProperty(key, def).trim();
    }

    private static List<String> list(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static Path path(String s) {
        return s.isBlank() ? null : Path.of(s);
    }
}
