package com.localis.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localis.config.AppConfig;
import com.localis.net.NetworkGuard;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class OllamaClient implements LlmClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI baseUri;
    private final String chatModel;
    private final String embedModel;
    private final int numCtx;
    private final Duration timeout;
    private final HttpClient http;

    public OllamaClient(AppConfig cfg, NetworkGuard guard) {
        this.baseUri = URI.create(cfg.ollamaBaseUrl().replaceAll("/+$", "") + "/");
        guard.check(baseUri);
        this.chatModel = cfg.chatModel();
        this.embedModel = cfg.embedModel();
        this.numCtx = cfg.numCtx();
        this.timeout = Duration.ofSeconds(cfg.llmTimeoutSeconds());
        // Sin redirecciones: una respuesta no puede desviarnos a otro host.
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String chat(List<Message> messages) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", chatModel);
        body.put("stream", false);
        ObjectNode options = body.putObject("options");
        options.put("temperature", 0.1);
        options.put("num_ctx", numCtx);
        ArrayNode arr = body.putArray("messages");
        for (Message m : messages) {
            arr.addObject().put("role", m.role()).put("content", m.content());
        }
        JsonNode resp = post("api/chat", body);
        JsonNode content = resp.path("message").path("content");
        if (content.isMissingNode()) {
            throw new LlmException("Respuesta de Ollama sin contenido: " + resp);
        }
        return content.asText();
    }

    @Override
    public List<float[]> embed(List<String> inputs) {
        if (inputs.isEmpty()) {
            return List.of();
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("model", embedModel);
        ArrayNode arr = body.putArray("input");
        inputs.forEach(arr::add);
        JsonNode resp = post("api/embed", body);
        JsonNode embeddings = resp.path("embeddings");
        if (!embeddings.isArray() || embeddings.size() != inputs.size()) {
            throw new LlmException("Ollama devolvio " + embeddings.size() + " embeddings para " + inputs.size()
                    + " textos");
        }
        List<float[]> out = new ArrayList<>(inputs.size());
        for (JsonNode vec : embeddings) {
            float[] v = new float[vec.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) vec.get(i).asDouble();
            }
            out.add(v);
        }
        return out;
    }

    @Override
    public Status status() {
        try {
            HttpRequest req = HttpRequest.newBuilder(baseUri.resolve("api/tags"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                return new Status(false, false, false, "HTTP " + r.statusCode());
            }
            JsonNode models = JSON.readTree(r.body()).path("models");
            boolean chat = false;
            boolean embed = false;
            for (JsonNode m : models) {
                String name = m.path("name").asText();
                chat |= sameModel(name, chatModel);
                embed |= sameModel(name, embedModel);
            }
            return new Status(true, chat, embed, "ok");
        } catch (IOException e) {
            return new Status(false, false, false, describe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Status(false, false, false, "interrumpido");
        }
    }

    // "nomic-embed-text" y "nomic-embed-text:latest" son el mismo modelo.
    static boolean sameModel(String installed, String wanted) {
        String a = installed.contains(":") ? installed : installed + ":latest";
        String b = wanted.contains(":") ? wanted : wanted + ":latest";
        return a.equals(b);
    }

    private static String describe(IOException e) {
        return e.getMessage() != null ? e.getMessage() : "conexion rechazada (" + e.getClass().getSimpleName() + ")";
    }

    private JsonNode post(String path, ObjectNode body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(baseUri.resolve(path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new LlmException("Ollama respondio HTTP " + r.statusCode() + ": " + r.body());
            }
            return JSON.readTree(r.body());
        } catch (IOException e) {
            throw new LlmException("No se pudo contactar con Ollama en " + baseUri + ": " + describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Solicitud a Ollama interrumpida", e);
        }
    }
}
