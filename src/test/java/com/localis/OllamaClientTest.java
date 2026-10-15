package com.localis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localis.config.AppConfig;
import com.localis.llm.LlmClient;
import com.localis.llm.LlmException;
import com.localis.llm.OllamaClient;
import com.localis.net.NetworkGuard;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OllamaClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    HttpServer fake;
    AtomicReference<JsonNode> lastBody = new AtomicReference<>();
    int tagsStatus = 200;

    @BeforeEach
    void start() throws Exception {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/api/chat", ex -> {
            lastBody.set(JSON.readTree(ex.getRequestBody()));
            reply(ex, 200, "{\"message\":{\"role\":\"assistant\",\"content\":\"hola\"}}");
        });
        fake.createContext("/api/embed", ex -> {
            JsonNode body = JSON.readTree(ex.getRequestBody());
            lastBody.set(body);
            StringBuilder sb = new StringBuilder("{\"embeddings\":[");
            for (int i = 0; i < body.get("input").size(); i++) {
                sb.append(i > 0 ? "," : "").append("[0.5,").append(i).append("]");
            }
            reply(ex, 200, sb.append("]}").toString());
        });
        fake.createContext("/api/tags", ex -> reply(ex, tagsStatus,
                "{\"models\":[{\"name\":\"codellama:13b-instruct\"},{\"name\":\"nomic-embed-text:latest\"}]}"));
        // Hilos propios (daemon): un handler bloqueado no debe parar el servidor falso ni retrasar el fin de la JVM.
        fake.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        fake.start();
    }

    @AfterEach
    void stop() {
        fake.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private OllamaClient client(String baseUrl) {
        Properties p = new Properties();
        p.setProperty("ollama.baseUrl", baseUrl);
        return new OllamaClient(AppConfig.from(p), new NetworkGuard(List.of("localhost", "127.0.0.1", "::1")));
    }

    private OllamaClient client() {
        return client("http://127.0.0.1:" + fake.getAddress().getPort());
    }

    @Test
    void chatSendsModelMessagesAndLowTemperature() {
        String out = client().chat(List.of(LlmClient.Message.system("s"), LlmClient.Message.user("u")));
        assertEquals("hola", out);
        JsonNode b = lastBody.get();
        assertEquals("codellama:13b-instruct", b.get("model").asText());
        assertTrue(b.get("stream").asBoolean(), "se lee en streaming para poder cortar los bucles");
        assertEquals(2, b.get("messages").size());
        assertEquals("user", b.get("messages").get(1).get("role").asText());
        assertEquals(0.1, b.get("options").get("temperature").asDouble(), 1e-9);
        assertEquals(1500, b.get("options").get("num_predict").asInt(), "tope de generacion");
        assertEquals("30m", b.get("keep_alive").asText());
        assertFalse(b.get("options").has("repeat_penalty"));
    }

    @Test
    void embedReturnsOneVectorPerInput() {
        List<float[]> v = client().embed(List.of("a", "b", "c"));
        assertEquals(3, v.size());
        assertArrayEquals(new float[] {0.5f, 2f}, v.get(2));
        assertEquals("nomic-embed-text", lastBody.get().get("model").asText());
    }

    @Test
    void statusDetectsInstalledModelsIgnoringLatestTag() {
        LlmClient.Status s = client().status();
        assertTrue(s.reachable() && s.chatModelInstalled() && s.embedModelInstalled());
    }

    @Test
    void unreachableOllamaIsReportedNotThrownByStatus() {
        fake.stop(0);
        LlmClient.Status s = client().status();
        assertFalse(s.reachable());
        assertThrows(LlmException.class, () -> client().chat(List.of(LlmClient.Message.user("x"))));
    }

    @Test
    void httpErrorsBecomeLlmException() {
        fake.removeContext("/api/chat");
        fake.createContext("/api/chat", ex -> reply(ex, 500, "boom"));
        assertThrows(LlmException.class, () -> client().chat(List.of(LlmClient.Message.user("x"))));
    }

    @Test
    void remoteHostsAreRefusedAtConstruction() {
        assertThrows(SecurityException.class, () -> client("https://api.example.com"));
    }

    private static void stream(com.sun.net.httpserver.HttpExchange ex, java.util.function.IntFunction<String> pieces,
            int count, String doneLine) throws java.io.IOException {
        ex.sendResponseHeaders(200, 0);
        var out = ex.getResponseBody();
        try {
            for (int i = 0; i < count; i++) {
                String piece = pieces.apply(i);
                out.write(("{\"message\":{\"role\":\"assistant\",\"content\":" + JSON.writeValueAsString(piece)
                        + "},\"done\":false}\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            if (doneLine != null) {
                out.write((doneLine + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (java.io.IOException clientLeft) {
            // el cliente corto la conexion: es lo que se espera cuando detecta un bucle
        } finally {
            ex.close();
        }
    }

    private static final String DONE = "{\"message\":{\"content\":\"\"},\"done\":true,\"done_reason\":\"stop\","
            + "\"prompt_eval_count\":50,\"eval_count\":7,\"load_duration\":1000000000,"
            + "\"prompt_eval_duration\":500000000,\"eval_duration\":2000000000,\"total_duration\":3500000000}";
    private static final String LOOP_UNIT = "El calculo del IVA mensual se realiza en el archivo QueriesISROpcional.sql. ";

    @Test
    void chatJoinsStreamedPiecesAndRecordsTimings() throws Exception {
        fake.removeContext("/api/chat");
        fake.createContext("/api/chat", ex -> stream(ex, i -> new String[] {"Ho", "la ", "mundo"}[i], 3, DONE));
        try (var ignored = com.localis.llm.LlmStats.collect()) {
            assertEquals("Hola mundo", client().chat(List.of(LlmClient.Message.user("u"))));
            var s = com.localis.llm.LlmStats.summary();
            assertEquals(1, s.get("modelCalls"));
            assertEquals(50, s.get("promptTokens"));
            assertEquals(7, s.get("generatedTokens"));
            assertEquals(1.0, (double) s.get("loadSeconds"), 1e-6);
            assertEquals(3.5, (double) s.get("tokensPerSecond"), 1e-6);
        }
        assertFalse(com.localis.llm.LlmStats.consumeTruncated());
    }

    @Test
    void aLoopingModelIsCutImmediatelyAndRetriedOnceWithDifferentSampling() throws Exception {
        java.util.List<JsonNode> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger sent = new java.util.concurrent.atomic.AtomicInteger();
        fake.removeContext("/api/chat");
        fake.createContext("/api/chat", ex -> {
            JsonNode body = JSON.readTree(ex.getRequestBody());
            bodies.add(body);
            if (bodies.size() == 1) {
                stream(ex, i -> {
                    sent.incrementAndGet();
                    return i == 0 ? "Respuesta: " : LOOP_UNIT;
                }, 5000, null);
            } else {
                stream(ex, i -> "Respuesta correcta.", 1, DONE);
            }
        });
        try (var ignored = com.localis.llm.LlmStats.collect()) {
            long t0 = System.nanoTime();
            assertEquals("Respuesta correcta.", client().chat(List.of(LlmClient.Message.user("u"))));
            assertTrue((System.nanoTime() - t0) / 1e9 < 10, "no espera a agotar las 5000 repeticiones");
            assertEquals(1L, com.localis.llm.LlmStats.summary().get("loopRetries"));
        }
        assertEquals(2, bodies.size());
        assertEquals(0.5, bodies.get(1).get("options").get("temperature").asDouble(), 1e-9);
        assertEquals(1.15, bodies.get(1).get("options").get("repeat_penalty").asDouble(), 1e-9);
        assertTrue(sent.get() < 1000, "el cliente corto la conexion pronto: " + sent.get());
        assertFalse(com.localis.llm.LlmStats.consumeTruncated());
    }

    @Test
    void ifTheLoopPersistsTheUsefulPartIsReturnedAndMarkedTruncated() {
        fake.removeContext("/api/chat");
        fake.createContext("/api/chat", ex -> stream(ex, i -> i == 0 ? "Inicio util. " : LOOP_UNIT, 5000, null));
        String out = client().chat(List.of(LlmClient.Message.user("u")));
        assertEquals("Inicio util. " + LOOP_UNIT, out);
        assertTrue(com.localis.llm.LlmStats.consumeTruncated());
    }

    @Test
    void hittingTheTokenCapIsFlaggedAsTruncated() {
        fake.removeContext("/api/chat");
        fake.createContext("/api/chat", ex -> stream(ex, i -> "texto", 1,
                "{\"message\":{\"content\":\"\"},\"done\":true,\"done_reason\":\"length\",\"eval_count\":1500}"));
        assertEquals("texto", client().chat(List.of(LlmClient.Message.user("u"))));
        assertTrue(com.localis.llm.LlmStats.consumeTruncated());
        assertFalse(com.localis.llm.LlmStats.consumeTruncated(), "la marca se consume");
    }

    @Test
    void interruptingTheThreadAbortsAStalledStream() throws Exception {
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        fake.removeContext("/api/chat");
        fake.createContext("/api/chat", ex -> {
            ex.sendResponseHeaders(200, 0);
            ex.getResponseBody().write("{\"message\":{\"content\":\"a\"},\"done\":false}\n".getBytes());
            ex.getResponseBody().flush();
            started.countDown();
            try {
                release.await(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // fin del test
            }
            ex.close();
        });
        var result = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread t = new Thread(() -> {
            try {
                client().chat(List.of(LlmClient.Message.user("u")));
            } catch (Throwable e) {
                result.set(e);
            }
        });
        t.start();
        try {
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            Thread.sleep(200);
            t.interrupt();
            t.join(5000);
            assertFalse(t.isAlive(), "la lectura bloqueada debe terminar al interrumpir");
            assertTrue(result.get() instanceof LlmException, String.valueOf(result.get()));
        } finally {
            release.countDown();
        }
    }
}
