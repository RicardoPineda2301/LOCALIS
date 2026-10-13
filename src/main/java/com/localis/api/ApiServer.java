package com.localis.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localis.config.AppConfig;
import com.localis.docs.DocumentationService;
import com.localis.index.Indexer;
import com.localis.llm.LlmClient;
import com.localis.llm.LlmException;
import com.localis.rag.AnswerResult;
import com.localis.rag.NotFoundException;
import com.localis.workspace.FolderBrowser;
import com.localis.workspace.Workspace;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
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

    /** El repositorio activo (indice, indexador, servicios de IA) vive en {@link Workspace} y puede cambiar. */
    public record Deps(AppConfig config, LlmClient llm, Workspace workspace, DocumentationService docs,
            Predicate<String> moduleAllowed, Supplier<Map<String, Object>> licenseStatus) {}

    record ExplainRequest(String path, String question) {}

    record DiagnoseRequest(String problem) {}

    record WorkspaceRequest(String path) {}

    /** type: "explain" (path y/o question) o "diagnose" (problem). */
    record JobRequest(String type, String path, String question, String problem) {}

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
    private final JobManager jobs = new JobManager(ApiServer::classify, 45_000);

    public ApiServer(Deps deps) throws IOException {
        this.d = deps;
        this.server = HttpServer.create(new InetSocketAddress(deps.config().serverHost(),
                deps.config().serverPort()), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        route("GET", "/api/health", false, ex -> health());
        route("GET", "/api/workspace", true, ex -> Response.json(200, workspaceInfo()));
        route("POST", "/api/workspace", true, ex -> {
            requireLocal(ex);
            if (jobs.isBusy()) {
                throw new Workspace.BusyException("Hay un analisis en curso; cancelelo o espere a que termine");
            }
            String p = body(ex, WorkspaceRequest.class).path();
            if (p == null || p.isBlank()) {
                throw new IllegalArgumentException("Indique 'path'");
            }
            d.workspace().open(parsePath(p));
            return Response.json(200, workspaceInfo());
        });
        route("GET", "/api/fs/list", true, ex -> {
            requireLocal(ex);
            return Response.json(200, FolderBrowser.list(query(ex, "path")));
        });
        route("GET", "/api/repo/tree", true, ex ->
                Response.json(200, d.workspace().scanner().list(query(ex, "path"))));
        route("POST", "/api/index", true, ex -> startIndex());
        route("GET", "/api/index/status", true, ex -> {
            Indexer idx = d.workspace().indexer();
            return Response.json(200, idx == null ? Map.of("state", "NOT_CONFIGURED") : idx.status());
        });
        route("POST", "/api/explain", true, ex -> {
            requireModule("explain");
            ExplainRequest r = body(ex, ExplainRequest.class);
            boolean wholeFile = r.path() != null && !r.path().isBlank();
            return answer(d.workspace().explain().explain(r.path(), r.question(),
                    ex.getRequestHeaders().getFirst("Accept-Language")), !wholeFile);
        });
        route("POST", "/api/diagnose", true, ex -> {
            requireModule("diagnose");
            return answer(d.workspace().diagnose().diagnose(body(ex, DiagnoseRequest.class).problem(),
                    ex.getRequestHeaders().getFirst("Accept-Language")), true);
        });
        // Analisis largos en segundo plano: con progreso, cancelables y de uno en uno.
        route("POST", "/api/job", true, ex -> {
            JobManager.Job job = jobs.start(prepareJob(ex));
            return Response.json(202, jobs.snapshot(job));
        });
        route("GET", "/api/job", true, ex -> Response.json(200, jobs.snapshot(jobOrNotFound(ex, false))));
        route("DELETE", "/api/job", true, ex -> Response.json(200, jobs.snapshot(jobOrNotFound(ex, true))));
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
        server.createContext("/", new StaticFiles());
    }

    public void start() {
        server.start();
        LOG.info("LOCALIS escuchando en http://{}:{}", d.config().serverHost(), port());
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        jobs.shutdown();
        server.stop(0);
    }

    private Response health() throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        LlmClient.Status s = d.llm().status();
        out.put("llm", Map.of("reachable", s.reachable(), "chatModelInstalled", s.chatModelInstalled(),
                "embedModelInstalled", s.embedModelInstalled(), "detail", String.valueOf(s.detail()),
                "chatModel", d.config().chatModel(), "embedModel", d.config().embedModel()));
        out.put("index", d.workspace().stats());
        out.put("license", d.licenseStatus().get());
        return Response.json(200, out);
    }

    private Map<String, Object> workspaceInfo() {
        Map<String, Object> out = new LinkedHashMap<>();
        var repo = d.workspace().repo();
        out.put("path", repo.map(Path::toString).orElse(null));
        out.put("name", repo.map(p -> p.getFileName() == null ? p.toString() : p.getFileName().toString())
                .orElse(null));
        return out;
    }

    private Response startIndex() throws IOException {
        Indexer indexer = d.workspace().indexer();
        if (indexer == null) {
            return Response.json(409, Map.of("error", "No hay repositorio configurado"));
        }
        boolean started = indexer.startAsync();
        return Response.json(started ? 202 : 409, started ? indexer.status()
                : Map.of("error", "Ya hay una indexacion en curso"));
    }

    /** Al explicar un fichero entero el usuario ya tiene ese codigo delante: no se adjuntan funciones sueltas. */
    private Response answer(AnswerResult r, boolean withSnippets) throws IOException {
        return Response.json(200, answerMap(r, withSnippets));
    }

    private Map<String, Object> answerMap(AnswerResult r, boolean withSnippets) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("answer", r.answer());
        out.put("allCitationsVerified", r.allCitationsVerified());
        out.put("citations", r.citations());
        out.put("snippets", withSnippets ? d.workspace().snippets(r) : List.of());
        out.put("sources", r.sources());
        out.put("warnings", r.warnings());
        return out;
    }

    /** Valida y prepara el analisis en el hilo de la peticion (errores 400/403 inmediatos); lo pesado va en el trabajo. */
    private Callable<Object> prepareJob(HttpExchange ex) throws IOException {
        JobRequest r = body(ex, JobRequest.class);
        String lang = ex.getRequestHeaders().getFirst("Accept-Language");
        if ("explain".equals(r.type())) {
            requireModule("explain");
            var service = d.workspace().explain();
            boolean wholeFile = r.path() != null && !r.path().isBlank();
            return () -> answerMap(service.explain(r.path(), r.question(), lang), !wholeFile);
        } else if ("diagnose".equals(r.type())) {
            requireModule("diagnose");
            var service = d.workspace().diagnose();
            return () -> answerMap(service.diagnose(r.problem(), lang), true);
        }
        throw new IllegalArgumentException("'type' debe ser 'explain' o 'diagnose'");
    }

    private JobManager.Job jobOrNotFound(HttpExchange ex, boolean cancel) {
        String id = query(ex, "id");
        JobManager.Job job = cancel ? jobs.cancel(id) : jobs.get(id);
        if (job == null) {
            throw new NotFoundException("Trabajo no encontrado: " + id);
        }
        return job;
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

    private record Route(boolean auth, Handler handler) {}

    private final Map<String, Map<String, Route>> routes = new java.util.HashMap<>();

    /** Una misma ruta puede tener varios metodos; el contexto HTTP se crea solo la primera vez. */
    private void route(String method, String path, boolean auth, Handler h) {
        Map<String, Route> byMethod = routes.get(path);
        if (byMethod == null) {
            Map<String, Route> created = new java.util.HashMap<>();
            routes.put(path, created);
            server.createContext(path, ex -> dispatch(ex, path, created));
            byMethod = created;
        }
        byMethod.put(method, new Route(auth, h));
    }

    private void dispatch(HttpExchange ex, String path, Map<String, Route> byMethod) throws IOException {
        try (ex) {
            Response r;
            try {
                Route route = byMethod.get(ex.getRequestMethod());
                if (!ex.getRequestURI().getPath().equals(path)) {
                    r = Response.json(404, Map.of("error", "No encontrado"));
                } else if (route == null) {
                    r = Response.json(405, Map.of("error", "Metodo no permitido"));
                } else if (route.auth() && !authorized(ex)) {
                    r = Response.json(401, Map.of("error", "Token invalido o ausente"));
                } else {
                    r = route.handler().handle(ex);
                }
            } catch (Exception e) {
                JobManager.Failure f = classify(e);
                if (f.status() == 500) {
                    LOG.error("Error procesando {} {}", ex.getRequestMethod(), path, e);
                }
                r = Response.json(f.status(), Map.of("error", f.message()));
            }
            send(ex, r);
        }
    }

    /** Traduce una excepcion al codigo HTTP y mensaje que ve el cliente (tambien para los trabajos en segundo plano). */
    private static JobManager.Failure classify(Exception e) {
        String msg = String.valueOf(e.getMessage());
        if (e instanceof IllegalArgumentException) {
            return new JobManager.Failure(400, msg);
        } else if (e instanceof Workspace.BusyException) {
            return new JobManager.Failure(409, msg);
        } else if (e instanceof SecurityException || e instanceof LicenseDeniedException) {
            return new JobManager.Failure(403, msg);
        } else if (e instanceof NotFoundException) {
            return new JobManager.Failure(404, msg);
        } else if (e instanceof LlmException) {
            return new JobManager.Failure(502, msg);
        }
        return new JobManager.Failure(500, "Error interno");
    }

    /**
     * Operaciones sensibles (explorar el disco, cambiar de repositorio): solo desde el propio equipo, con Host de
     * loopback y, si hay Origin, del mismo origen. Frena tambien CSRF y DNS rebinding desde paginas web externas.
     */
    private void requireLocal(HttpExchange ex) {
        String msg = "Esta operacion solo esta permitida desde el propio equipo";
        if (!ex.getRemoteAddress().getAddress().isLoopbackAddress()) {
            throw new SecurityException(msg);
        }
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || !isLocalHost(hostName(host))) {
            throw new SecurityException(msg);
        }
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null) {
            try {
                String authority = java.net.URI.create(origin).getAuthority();
                if (authority == null || !authority.equalsIgnoreCase(host)) {
                    throw new SecurityException(msg);
                }
            } catch (IllegalArgumentException e) {
                throw new SecurityException(msg);
            }
        }
    }

    private static String hostName(String hostHeader) {
        if (hostHeader.startsWith("[")) {
            int end = hostHeader.indexOf(']');
            return end > 0 ? hostHeader.substring(0, end + 1) : hostHeader;
        }
        int colon = hostHeader.lastIndexOf(':');
        return colon > 0 ? hostHeader.substring(0, colon) : hostHeader;
    }

    private boolean isLocalHost(String name) {
        return name.equalsIgnoreCase("localhost") || name.equals("127.0.0.1") || name.equals("[::1]")
                || name.equalsIgnoreCase(d.config().serverHost());
    }

    private static Path parsePath(String p) {
        try {
            return Path.of(p);
        } catch (java.nio.file.InvalidPathException e) {
            throw new IllegalArgumentException("Ruta no valida: " + p);
        }
    }

    private static String query(HttpExchange ex, String name) {
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) {
            return "";
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (k.equals(name)) {
                return eq < 0 ? "" : java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
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
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.toLowerCase().startsWith("application/json")) {
            // Exigirlo obliga al navegador a hacer preflight CORS, que bloquea las peticiones de otros sitios.
            throw new IllegalArgumentException("Content-Type debe ser application/json");
        }
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
