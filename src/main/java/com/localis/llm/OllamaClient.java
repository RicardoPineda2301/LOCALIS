package com.localis.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localis.config.AppConfig;
import com.localis.net.NetworkGuard;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class OllamaClient implements LlmClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI baseUri;
    private final String chatModel;
    private final String embedModel;
    private final int numCtx;
    private final String keepAlive;
    private final int maxTokens;
    private final Duration timeout;
    private final HttpClient http;

    public OllamaClient(AppConfig cfg, NetworkGuard guard) {
        this.baseUri = URI.create(cfg.ollamaBaseUrl().replaceAll("/+$", "") + "/");
        guard.check(baseUri);
        this.chatModel = cfg.chatModel();
        this.embedModel = cfg.embedModel();
        this.numCtx = cfg.numCtx();
        this.keepAlive = cfg.keepAlive();
        this.maxTokens = cfg.maxTokens();
        this.timeout = Duration.ofSeconds(cfg.llmTimeoutSeconds());
        // Sin redirecciones: una respuesta no puede desviarnos a otro host.
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private record Attempt(String text, boolean looped, boolean capped) {}

    @Override
    public String chat(List<Message> messages) {
        Attempt a = chatOnce(messages, 0.1, 1.0);
        if (a.looped()) {
            // Otro muestreo suele sacar al modelo del bucle; si vuelve a caer se entrega lo util y se avisa.
            a = chatOnce(messages, 0.5, 1.15);
        }
        String text = a.text();
        boolean truncated = a.capped();
        if (a.looped()) {
            int cut = Repetition.loopCut(text);
            text = cut > 0 ? text.substring(0, cut) : text;
            truncated = true;
        }
        if (truncated) {
            LlmStats.markTruncated();
        }
        return text;
    }

    /** Una generacion en streaming; si detecta un bucle de repeticion corta la conexion al instante. */
    private Attempt chatOnce(List<Message> messages, double temperature, double repeatPenalty) {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", chatModel);
        body.put("stream", true);
        body.put("keep_alive", keepAlive);
        ObjectNode options = body.putObject("options");
        options.put("temperature", temperature);
        options.put("num_ctx", numCtx);
        // Sin tope, un bucle de repeticion del modelo no termina nunca (Ollama desplaza el contexto y sigue).
        options.put("num_predict", maxTokens);
        if (repeatPenalty != 1.0) {
            options.put("repeat_penalty", repeatPenalty);
        }
        ArrayNode arr = body.putArray("messages");
        for (Message m : messages) {
            arr.addObject().put("role", m.role()).put("content", m.content());
        }
        long start = System.nanoTime();
        try {
            HttpRequest req = HttpRequest.newBuilder(baseUri.resolve("api/chat"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                    .build();
            HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) {
                try (InputStream in = r.body()) {
                    throw new LlmException("Ollama respondio HTTP " + r.statusCode() + ": "
                            + new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            StringBuilder text = new StringBuilder();
            JsonNode last = null;
            boolean looped = false;
            int chunks = 0;
            int checkedAt = 0;
            // Cerrar el stream cancela la peticion: Ollama deja de generar.
            try (BufferedReader br = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode n = JSON.readTree(line);
                    if (n.hasNonNull("error")) {
                        throw new LlmException("Ollama: " + n.get("error").asText());
                    }
                    text.append(n.path("message").path("content").asText(""));
                    chunks++;
                    if (n.path("done").asBoolean(false)) {
                        last = n;
                        break;
                    }
                    if (text.length() - checkedAt >= 40) {
                        checkedAt = text.length();
                        if (Repetition.loopCut(text.toString()) >= 0) {
                            looped = true;
                            break;
                        }
                    }
                    if (System.nanoTime() - start > timeout.toNanos()) {
                        throw new LlmException("Tiempo de espera agotado esperando al modelo");
                    }
                }
            }
            double elapsed = (System.nanoTime() - start) / 1e9;
            if (last != null) {
                LlmStats.record(new LlmStats.Call("chat", last.path("prompt_eval_count").asInt(),
                        last.path("eval_count").asInt(), nanos(last, "load_duration"),
                        nanos(last, "prompt_eval_duration"), nanos(last, "eval_duration"),
                        nanos(last, "total_duration"), looped));
            } else {
                LlmStats.record(new LlmStats.Call("chat", 0, chunks, 0, 0, elapsed, elapsed, looped));
            }
            if (last == null && !looped && text.length() == 0) {
                throw new LlmException("Respuesta de Ollama sin contenido");
            }
            boolean capped = last != null && "length".equals(last.path("done_reason").asText());
            return new Attempt(text.toString(), looped, capped);
        } catch (InterruptedIOException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Solicitud a Ollama interrumpida", e);
        } catch (IOException e) {
            throw new LlmException("No se pudo contactar con Ollama en " + baseUri + ": " + describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Solicitud a Ollama interrumpida", e);
        }
    }

    private static double nanos(JsonNode resp, String field) {
        return resp.path(field).asLong() / 1e9;
    }

    @Override
    public List<float[]> embed(List<String> inputs) {
        if (inputs.isEmpty()) {
            return List.of();
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("model", embedModel);
        body.put("keep_alive", keepAlive);
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
