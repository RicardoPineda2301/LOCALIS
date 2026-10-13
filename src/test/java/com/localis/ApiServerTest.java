package com.localis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localis.TestSupport.FakeLlm;
import com.localis.api.ApiServer;
import com.localis.config.AppConfig;
import com.localis.docs.DocumentationService;
import com.localis.index.Indexer;
import com.localis.workspace.Workspace;
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
    Workspace workspace;
    ApiServer server;
    HttpClient http = HttpClient.newHttpClient();
    String base;
    Indexer indexer;
    Path repo;

    @BeforeEach
    void start() throws Exception {
        startWith("", s -> true);
    }

    private void startWith(String token, Predicate<String> modules) throws Exception {
        if (server != null) {
            server.stop();
            workspace.close();
        }
        repo = TestSupport.sampleRepo(Files.createDirectories(tmp.resolve("repo")));
        Properties p = new Properties();
        p.setProperty("server.port", "0");
        p.setProperty("server.apiToken", token);
        p.setProperty("templates.dir", "templates");
        p.setProperty("branding.company", "ACME");
        p.setProperty("data.dir", tmp.resolve("data" + System.nanoTime()).toString().replace('\\', '/'));
        p.setProperty("repo.path", repo.toString().replace('\\', '/'));
        AppConfig cfg = AppConfig.from(p);

        workspace = new Workspace(cfg, llm);
        indexer = workspace.indexer();
        server = new ApiServer(new ApiServer.Deps(cfg, llm, workspace,
                new DocumentationService(cfg.templatesDir(), "ACME", null), modules,
                () -> Map.of("enforced", false)));
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void stop() {
        server.stop();
        workspace.close();
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
        JsonNode snip = j.get("snippets").get(0);
        assertEquals("src/com/acme/billing/InvoiceCalculator.java", snip.get("path").asText());
        assertEquals("InvoiceCalculator.calculateInvoiceTotal", snip.get("symbol").asText());
        assertTrue(snip.get("code").asText().contains("double tax = subtotal * TAX_RATE;"));
        assertEquals(15, snip.get("highlight").get(0).asInt());
    }

    @Test
    void snippetIsInferredWhenTheModelDoesNotUseTheCitationFormat() throws Exception {
        indexAndWait();
        llm.chatHandler = m -> m.get(0).content().contains("comma-separated") ? "invoice"
                : "El error está en la línea 15, el redondeo es incorrecto.";
        var r = call("POST", "/api/diagnose", "{\"problem\":\"el total de la factura sale mal\"}", null);
        JsonNode j = json(r);
        assertEquals(0, j.get("citations").size());
        assertEquals("InvoiceCalculator.calculateInvoiceTotal", j.get("snippets").get(0).get("symbol").asText());
        assertEquals(15, j.get("snippets").get(0).get("highlight").get(0).asInt());
    }

    @Test
    void jobRunsDiagnoseInTheBackgroundAndReturnsTheAnswerWithSnippets() throws Exception {
        indexAndWait();
        llm.chatHandler = m -> m.get(0).content().contains("comma-separated") ? "invoice"
                : "Causa probable: InvoiceCalculator.java:15";
        var start = call("POST", "/api/job", "{\"type\":\"diagnose\",\"problem\":\"el total de la factura sale mal\"}",
                null);
        assertEquals(202, start.statusCode());

        JsonNode done = awaitJob(json(start).get("id").asText());
        assertEquals("DONE", done.get("state").asText());
        assertTrue(done.get("result").get("answer").asText().contains("Causa probable"));
        assertEquals("InvoiceCalculator.calculateInvoiceTotal",
                done.get("result").get("snippets").get(0).get("symbol").asText());
    }

    @Test
    void jobExplainingAWholeFileAttachesNoSnippetsAndBadRequestsAreRejected() throws Exception {
        indexAndWait();
        llm.chatHandler = m -> "Saluda. CustomerService.java:4";
        var start = call("POST", "/api/job",
                "{\"type\":\"explain\",\"path\":\"src/com/acme/crm/CustomerService.java\"}", null);
        JsonNode done = awaitJob(json(start).get("id").asText());
        assertEquals("DONE", done.get("state").asText());
        assertEquals(0, done.get("result").get("snippets").size());

        assertEquals(400, call("POST", "/api/job", "{\"type\":\"otra\"}", null).statusCode());
        assertEquals(404, call("GET", "/api/job?id=no-existe", null, null).statusCode());
        var empty = call("POST", "/api/job", "{\"type\":\"explain\"}", null);
        JsonNode failed = awaitJob(json(empty).get("id").asText());
        assertEquals("FAILED", failed.get("state").asText());
        assertEquals(400, failed.get("status").asInt());
    }

    @Test
    void oneJobAtATimeAndCancellingAbortsTheModelCallInsteadOfQueueingBehindIt() throws Exception {
        indexAndWait();
        java.util.concurrent.CountDownLatch inModel = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean interrupted = new java.util.concurrent.atomic.AtomicBoolean();
        llm.chatHandler = m -> {
            inModel.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new com.localis.llm.LlmException("cancelado");
            }
            return "tarde";
        };
        var start = call("POST", "/api/job", "{\"type\":\"explain\",\"question\":\"que hace la factura\"}", null);
        String id = json(start).get("id").asText();
        assertTrue(inModel.await(5, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals(409, call("POST", "/api/job", "{\"type\":\"explain\",\"question\":\"otra\"}", null).statusCode());
        assertEquals(409, call("POST", "/api/workspace", JSON.writeValueAsString(Map.of("path", repo.toString())),
                null).statusCode());

        assertEquals("CANCELLED", json(call("DELETE", "/api/job?id=" + id, null, null)).get("state").asText());
        long end = System.currentTimeMillis() + 3000;
        while (!interrupted.get() && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }
        assertTrue(interrupted.get(), "Ollama solo deja de generar si se corta la llamada en curso");

        llm.chatHandler = m -> "Saluda. CustomerService.java:4";
        var again = call("POST", "/api/job", "{\"type\":\"explain\",\"question\":\"que hace la factura\"}", null);
        assertEquals(202, again.statusCode());
        assertEquals("DONE", awaitJob(json(again).get("id").asText()).get("state").asText());
    }

    private JsonNode awaitJob(String id) throws Exception {
        long end = System.currentTimeMillis() + 8000;
        JsonNode last = null;
        while (System.currentTimeMillis() < end) {
            last = json(call("GET", "/api/job?id=" + id, null, null));
            if (!last.get("state").asText().equals("RUNNING")) {
                return last;
            }
            Thread.sleep(20);
        }
        return last;
    }

    @Test
    void explainFileAndUnknownFile() throws Exception {
        indexAndWait();
        llm.chatHandler = m -> "Saluda. CustomerService.java:4";
        var ok = call("POST", "/api/explain", "{\"path\":\"src/com/acme/crm/CustomerService.java\"}", null);
        assertEquals(200, ok.statusCode(), new String(ok.body()));
        assertTrue(json(ok).get("allCitationsVerified").asBoolean());
        assertEquals(0, json(ok).get("snippets").size(), "explicar un fichero entero no adjunta funciones sueltas");

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

    @Test
    void webUiIsServedWithSecurityHeadersAndSpaFallback() throws Exception {
        var root = call("GET", "/", null, null);
        var spa = call("GET", "/alguna/ruta", null, null);
        assertEquals(root.statusCode(), spa.statusCode());
        assertTrue(root.statusCode() == 200 || root.statusCode() == 404);
        assertEquals("nosniff", root.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertTrue(root.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'self'"));
        if (root.statusCode() == 200) {
            assertTrue(root.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
            assertEquals(new String(root.body()), new String(spa.body()));
        }
    }

    @Test
    void webUiDoesNotShadowApiAndRejectsTraversalAndWrongMethods() throws Exception {
        var unknownApi = call("GET", "/api/nope", null, null);
        assertEquals(404, unknownApi.statusCode());
        assertEquals("No encontrado", json(unknownApi).get("error").asText());
        assertEquals(404, call("GET", "/missing.js", null, null).statusCode());
        assertEquals(404, call("GET", "/assets/..%2f..%2flocalis.properties", null, null).statusCode());
        assertEquals(404, call("GET", "/%2e%2e/%2e%2e/pom.xml", null, null).statusCode());
        assertEquals(405, call("POST", "/", "{}", null).statusCode());
    }

    @Test
    void switchingWorkspaceGivesAnEmptyIndexForTheNewRepo() throws Exception {
        assertEquals(repo.toString(), json(call("GET", "/api/workspace", null, null)).get("path").asText());
        indexAndWait();
        assertTrue(json(call("GET", "/api/health", null, null)).get("index").get("files").asInt() > 0);

        Path other = Files.createDirectories(tmp.resolve("otro"));
        Files.writeString(other.resolve("Hola.java"), "class Hola {}");
        String body = JSON.writeValueAsString(Map.of("path", other.toString()));
        var r = call("POST", "/api/workspace", body, null);
        assertEquals(200, r.statusCode());
        assertEquals("otro", json(r).get("name").asText());
        assertEquals(0, json(call("GET", "/api/health", null, null)).get("index").get("files").asInt());
        assertEquals("Hola.java", json(call("GET", "/api/repo/tree", null, null)).get(0).get("name").asText());

        // Volver al primer repo recupera su indice sin reindexar.
        call("POST", "/api/workspace", JSON.writeValueAsString(Map.of("path", repo.toString())), null);
        assertTrue(json(call("GET", "/api/health", null, null)).get("index").get("files").asInt() > 0);
    }

    @Test
    void workspaceRejectsInvalidPathsAndNonJsonBodies() throws Exception {
        assertEquals(400, call("POST", "/api/workspace", "{\"path\":\"" + tmp.resolve("nope")
                .toString().replace("\\", "\\\\") + "\"}", null).statusCode());
        assertEquals(400, call("POST", "/api/workspace", "{}", null).statusCode());
        var plain = HttpRequest.newBuilder(URI.create(base + "/api/workspace")).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("{\"path\":\"" + tmp.toString().replace("\\", "\\\\")
                        + "\"}")).build();
        assertEquals(400, http.send(plain, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void repoTreeListsFoldersFirstHidesIgnoredAndBlocksTraversal() throws Exception {
        JsonNode root = json(call("GET", "/api/repo/tree", null, null));
        var names = new java.util.ArrayList<String>();
        root.forEach(n -> names.add(n.get("name").asText()));
        assertTrue(names.contains("src") && names.contains("db"));
        assertTrue(!names.contains("target"));
        assertTrue(root.get(0).get("directory").asBoolean());

        JsonNode db = json(call("GET", "/api/repo/tree?path=db", null, null));
        assertEquals("db/tax.sql", db.get(0).get("path").asText());
        assertTrue(db.get(0).get("indexable").asBoolean());

        assertEquals(403, call("GET", "/api/repo/tree?path=..%2F", null, null).statusCode());
        assertEquals(400, call("GET", "/api/repo/tree?path=no-existe", null, null).statusCode());
    }

    @Test
    void folderBrowserListsFoldersOnlyAndIsLocalOnly() throws Exception {
        Files.writeString(tmp.resolve("suelto.txt"), "x");
        var r = call("GET", "/api/fs/list?path=" + java.net.URLEncoder.encode(tmp.toString(),
                java.nio.charset.StandardCharsets.UTF_8), null, null);
        assertEquals(200, r.statusCode());
        var names = new java.util.ArrayList<String>();
        json(r).get("entries").forEach(n -> names.add(n.get("name").asText()));
        assertTrue(names.contains("repo"));
        assertTrue(!names.contains("suelto.txt"));
        assertEquals(tmp.getParent().toString(), json(r).get("parent").asText());

        JsonNode top = json(call("GET", "/api/fs/list", null, null));
        assertTrue(top.get("entries").size() > 0);
        assertEquals(400, call("GET", "/api/fs/list?path=" + java.net.URLEncoder.encode(
                tmp.resolve("suelto.txt").toString(), java.nio.charset.StandardCharsets.UTF_8), null, null)
                .statusCode());

        var foreign = HttpRequest.newBuilder(URI.create(base + "/api/fs/list")).header("Origin", "http://evil.example")
                .GET().build();
        assertEquals(403, http.send(foreign, HttpResponse.BodyHandlers.ofString()).statusCode());
        var sameOrigin = HttpRequest.newBuilder(URI.create(base + "/api/fs/list")).header("Origin", base)
                .GET().build();
        assertEquals(200, http.send(sameOrigin, HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}
