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
        assertFalse(b.get("stream").asBoolean());
        assertEquals(2, b.get("messages").size());
        assertEquals("user", b.get("messages").get(1).get("role").asText());
        assertEquals(0.1, b.get("options").get("temperature").asDouble(), 1e-9);
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
}
