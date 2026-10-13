package com.localis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localis.TestSupport.FakeLlm;
import com.localis.api.ApiServer;
import com.localis.config.AppConfig;
import com.localis.docs.DocumentationService;
import com.localis.index.IndexStore;
import com.localis.index.Indexer;
import com.localis.index.RepositoryScanner;
import com.localis.rag.AnswerEngine;
import com.localis.rag.CitationVerifier;
import com.localis.rag.DiagnoseService;
import com.localis.rag.ExplainService;
import com.localis.search.Retriever;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    FakeLlm llm = new FakeLlm();
    IndexStore store;
    ApiServer server;
    HttpClient http = HttpClient.newHttpClient();
    String base;
    Indexer indexer;

    @BeforeEach
    void start() throws Exception {
        startWith("", s -> true);
    }

    private void startWith(String token, Predicate<String> modules) throws Exception {
        if (server != null) {
            server.stop();
            store.close();
        }
        Path repo = TestSupport.sampleRepo(Files.createDirectories(tmp.resolve("repo")));
        Properties p = new Properties();
        p.setProperty("server.port", "0");
        p.setProperty("server.apiToken", token);
        p.setProperty("templates.dir", "templates");
        p.setProperty("branding.company", "ACME");
        AppConfig cfg = AppConfig.from(p);

        store = new IndexStore(tmp.resolve("data" + System.nanoTime() + "/index.db"));
        var retriever = new Retriever(store, llm);
        var engine = new AnswerEngine(llm, new CitationVerifier(store::filePaths,
                x -> store.file(x).map(IndexStore.FileInfo::lines).orElse(0)));
        indexer = new Indexer(new RepositoryScanner(repo), store, llm);
        server = new ApiServer(new ApiServer.Deps(cfg, llm, store, indexer,
                new ExplainService(llm, engine, retriever, store, new RepositoryScanner(repo), 8, 16000),
                new DiagnoseService(llm, engine, retriever, 8, 16000),
                new DocumentationService(cfg.templatesDir(), "ACME", null), modules,
                () -> Map.of("enforced", false)));
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void stop() {
        server.stop();
        store.close();
    }

    private HttpResponse<byte[]> call(String method, String path, String body, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private JsonNode json(HttpResponse<byte[]> r) throws Exception {
        return JSON.readTree(r.body());
    }

    private void indexAndWait() throws Exception {
        assertEquals(202, call("POST", "/api/index", null, null).statusCode());
        indexer.awaitCompletion();
        assertEquals("DONE", json(call("GET", "/api/index/status", null, null)).get("state").asText());
    }

    @Test
    void healthReportsLlmIndexAndLicense() throws Exception {
        var r = call("GET", "/api/health", null, null);
        assertEquals(200, r.statusCode());
        JsonNode h = json(r);
        assertTrue(h.get("llm").get("reachable").asBoolean());
        assertEquals(0, h.get("index").get("files").asInt());
        assertEquals(false, h.get("license").get("enforced").asBoolean());
    }

    @Test
    void indexThenDiagnoseEndToEnd() throws Exception {
        indexAndWait();
        assertEquals(3, json(call("GET", "/api/health", null, null)).get("index").get("files").asInt());

        llm.chatHandler = m -> m.get(0).content().contains("comma-separated") ? "invoice"
                : "Causa probable: InvoiceCalculator.java:15";
        var r = call("POST", "/api/diagnose", "{\"problem\":\"el total de la factura sale mal\"}", null);
        assertEquals(200, r.statusCode(), new String(r.body()));
        JsonNode j = json(r);
        assertTrue(j.get("answer").asText().contains("Causa probable"));
        assertEquals("VERIFIED", j.get("citations").get(0).get("status").asText());
        assertEquals("src/com/acme/billing/InvoiceCalculator.java", j.get("citations").get(0).get("path").asText());
        assertTrue(j.get("allCitationsVerified").asBoolean());
    }

    @Test
    void explainFileAndUnknownFile() throws Exception {
        indexAndWait();
        llm.chatHandler = m -> "Saluda. CustomerService.java:4";
        var ok = call("POST", "/api/explain", "{\"path\":\"src/com/acme/crm/CustomerService.java\"}", null);
        assertEquals(200, ok.statusCode(), new String(ok.body()));
        assertTrue(json(ok).get("allCitationsVerified").asBoolean());

        assertEquals(404, call("POST", "/api/explain", "{\"path\":\"../secret.txt\"}", null).statusCode());
        assertEquals(400, call("POST", "/api/explain", "{}", null).statusCode());
    }

    @Test
    void generateDocxDownload() throws Exception {
        var list = json(call("GET", "/api/docs/templates", null, null));
        assertEquals(12, list.size());

        var r = call("POST", "/api/docs/generate",
                "{\"template\":\"5. API_Documentation_Template\",\"projectName\":\"Billing\",\"version\":\"1.2\"}", null);
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Disposition").orElse("").contains("Billing"));
        assertEquals('P', (char) r.body()[0]);
        assertEquals('K', (char) r.body()[1]);

        assertEquals(404, call("POST", "/api/docs/generate", "{\"template\":\"../pom\"}", null).statusCode());
    }

    @Test
    void badRequestsAreHandled() throws Exception {
        assertEquals(400, call("POST", "/api/diagnose", "no es json", null).statusCode());
        assertEquals(400, call("POST", "/api/diagnose", null, null).statusCode());
        assertEquals(400, call("POST", "/api/diagnose", "{\"problem\":\"" + "x".repeat(1_100_000) + "\"}", null)
                .statusCode());
        assertEquals(405, call("GET", "/api/diagnose", null, null).statusCode());
        assertEquals(404, call("GET", "/api/diagnose/extra", null, null).statusCode());
    }

    @Test
    void llmDownIsBadGateway() throws Exception {
        indexAndWait();
        llm.chatHandler = m -> {
            throw new com.localis.llm.LlmException("Ollama no responde");
        };
        var r = call("POST", "/api/explain", "{\"question\":\"que es una factura\"}", null);
        assertEquals(502, r.statusCode());
    }

    @Test
    void apiTokenIsRequiredExceptForHealth() throws Exception {
        startWith("s3cret", s -> true);
        assertEquals(200, call("GET", "/api/health", null, null).statusCode());
        assertEquals(401, call("GET", "/api/docs/templates", null, null).statusCode());
        assertEquals(401, call("GET", "/api/docs/templates", null, "wrong").statusCode());
        assertEquals(200, call("GET", "/api/docs/templates", null, "s3cret").statusCode());
    }

    @Test
    void modulesNotCoveredByTheLicenseAreForbidden() throws Exception {
        startWith("", m -> m.equals("docs"));
        assertEquals(403, call("POST", "/api/diagnose", "{\"problem\":\"x\"}", null).statusCode());
        assertEquals(403, call("POST", "/api/explain", "{\"question\":\"x\"}", null).statusCode());
        assertEquals(200, call("POST", "/api/docs/generate", "{\"template\":\"5. API_Documentation_Template\"}", null)
                .statusCode());
    }

    @Test
    void secondIndexRequestWhileRunningIsRejectedOrAccepted() throws Exception {
        assertEquals(202, call("POST", "/api/index", null, null).statusCode());
        int second = call("POST", "/api/index", null, null).statusCode();
        assertTrue(second == 409 || second == 202);
        indexer.awaitCompletion();
    }
}
