package com.localis.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localis.config.AppConfig;
import com.localis.docs.DocumentationService;
import com.localis.index.IndexStore;
import com.localis.index.Indexer;
import com.localis.llm.LlmClient;
import com.localis.llm.LlmException;
import com.localis.rag.AnswerResult;
import com.localis.rag.DiagnoseService;
import com.localis.rag.ExplainService;
import com.localis.rag.NotFoundException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** API REST local (sin dependencias externas: usa el servidor HTTP incluido en el JDK). */
public final class ApiServer {

    private static final Logger LOG = LoggerFactory.getLogger(ApiServer.class);
    private static final int MAX_BODY_BYTES = 1_000_000;
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Indexer puede ser null si no hay repo.path configurado. */
    public record Deps(AppConfig config, LlmClient llm, IndexStore store, Indexer indexer, ExplainService explain,
            DiagnoseService diagnose, DocumentationService docs, Predicate<String> moduleAllowed,
            Supplier<Map<String, Object>> licenseStatus) {}

    record ExplainRequest(String path, String question) {}

    record DiagnoseRequest(String problem) {}

    record DocRequest(String template, String projectName, String version, String author, String team) {}

    private record Response(int status, String contentType, byte[] body, Map<String, String> headers) {
        static Response json(int status, Object o) throws IOException {
            return new Response(status, "application/json; charset=utf-8", JSON.writeValueAsBytes(o), Map.of());
        }
    }

    private interface Handler {
        Response handle(HttpExchange ex) throws Exception;
    }

    private final Deps d;
    private final HttpServer server;

    public ApiServer(Deps deps) throws IOException {
        this.d = deps;
        this.server = HttpServer.create(new InetSocketAddress(deps.config().serverHost(),
                deps.config().serverPort()), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        route("GET", "/api/health", false, ex -> health());
        route("POST", "/api/index", true, ex -> startIndex());
        route("GET", "/api/index/status", true, ex -> Response.json(200, d.indexer() == null
                ? Map.of("state", "NOT_CONFIGURED") : d.indexer().status()));
        route("POST", "/api/explain", true, ex -> {
            requireModule("explain");
            ExplainRequest r = body(ex, ExplainRequest.class);
            return answer(d.explain().explain(r.path(), r.question()));
        });
        route("POST", "/api/diagnose", true, ex -> {
            requireModule("diagnose");
            return answer(d.diagnose().diagnose(body(ex, DiagnoseRequest.class).problem()));
        });
        route("GET", "/api/docs/templates", true, ex -> Response.json(200, d.docs().templates()));
        route("POST", "/api/docs/generate", true, ex -> {
            requireModule("docs");
            DocRequest r = body(ex, DocRequest.class);
            var doc = d.docs().generate(new DocumentationService.Request(r.template(), r.projectName(),
                    r.version(), r.author(), r.team()));
            String encoded = java.net.URLEncoder.encode(doc.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
            return new Response(200,
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", doc.content(),
                    Map.of("Content-Disposition", "attachment; filename*=UTF-8''" + encoded));
        });
    }

    public void start() {
        server.start();
        LOG.info("LOCALIS escuchando en http://{}:{}", d.config().serverHost(), port());
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }

    private Response health() throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        LlmClient.Status s = d.llm().status();
        out.put("llm", Map.of("reachable", s.reachable(), "chatModelInstalled", s.chatModelInstalled(),
                "embedModelInstalled", s.embedModelInstalled(), "detail", String.valueOf(s.detail()),
                "chatModel", d.config().chatModel(), "embedModel", d.config().embedModel()));
        out.put("index", d.store().stats());
        out.put("license", d.licenseStatus().get());
        return Response.json(200, out);
    }

    private Response startIndex() throws IOException {
        if (d.indexer() == null) {
            return Response.json(409, Map.of("error", "repo.path no esta configurado"));
        }
        boolean started = d.indexer().startAsync();
        return Response.json(started ? 202 : 409, started ? d.indexer().status()
                : Map.of("error", "Ya hay una indexacion en curso"));
    }

    private Response answer(AnswerResult r) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("answer", r.answer());
        out.put("allCitationsVerified", r.allCitationsVerified());
        out.put("citations", r.citations());
        out.put("sources", r.sources());
        out.put("warnings", r.warnings());
        return Response.json(200, out);
    }

    private void requireModule(String module) {
        if (!d.moduleAllowed().test(module)) {
            throw new LicenseDeniedException("Su licencia no incluye el modulo '" + module + "' o no es valida");
        }
    }

    static final class LicenseDeniedException extends RuntimeException {
        LicenseDeniedException(String m) {
            super(m);
        }
    }

    private void route(String method, String path, boolean auth, Handler h) {
        server.createContext(path, ex -> {
            try (ex) {
                Response r;
                try {
                    if (!ex.getRequestURI().getPath().equals(path)) {
                        r = Response.json(404, Map.of("error", "No encontrado"));
                    } else if (!ex.getRequestMethod().equals(method)) {
                        r = Response.json(405, Map.of("error", "Metodo no permitido"));
                    } else if (auth && !authorized(ex)) {
                        r = Response.json(401, Map.of("error", "Token invalido o ausente"));
                    } else {
                        r = h.handle(ex);
                    }
                } catch (IllegalArgumentException e) {
                    r = Response.json(400, Map.of("error", String.valueOf(e.getMessage())));
                } catch (NotFoundException e) {
                    r = Response.json(404, Map.of("error", e.getMessage()));
                } catch (LicenseDeniedException e) {
                    r = Response.json(403, Map.of("error", e.getMessage()));
                } catch (LlmException e) {
                    r = Response.json(502, Map.of("error", e.getMessage()));
                } catch (Exception e) {
                    LOG.error("Error procesando {} {}", ex.getRequestMethod(), path, e);
                    r = Response.json(500, Map.of("error", "Error interno"));
                }
                send(ex, r);
            }
        });
    }

    private boolean authorized(HttpExchange ex) {
        String token = d.config().apiToken();
        if (token.isEmpty()) {
            return true;
        }
        String header = ex.getRequestHeaders().getFirst("Authorization");
        return header != null && MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8),
                ("Bearer " + token).getBytes(StandardCharsets.UTF_8));
    }

    private static <T> T body(HttpExchange ex, Class<T> type) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > MAX_BODY_BYTES) {
                    throw new IllegalArgumentException("Peticion demasiado grande");
                }
            }
            if (bos.size() == 0) {
                throw new IllegalArgumentException("Cuerpo JSON requerido");
            }
            try {
                return JSON.readValue(bos.toByteArray(), type);
            } catch (IOException e) {
                throw new IllegalArgumentException("JSON invalido");
            }
        }
    }

    private static void send(HttpExchange ex, Response r) throws IOException {
        ex.getResponseHeaders().set("Content-Type", r.contentType());
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        r.headers().forEach((k, v) -> ex.getResponseHeaders().set(k, v));
        ex.sendResponseHeaders(r.status(), r.body().length == 0 ? -1 : r.body().length);
        if (r.body().length > 0) {
            ex.getResponseBody().write(r.body());
        }
    }
}
