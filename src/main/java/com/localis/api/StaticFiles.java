package com.localis.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Sirve la interfaz web (React) empaquetada en el jar bajo /web, con fallback a index.html para rutas de la SPA. */
final class StaticFiles implements HttpHandler {

    private static final String ROOT = "/web/";
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "json", "application/json; charset=utf-8",
            "woff2", "font/woff2",
            "map", "application/json; charset=utf-8");

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String method = ex.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                send(ex, 405, "text/plain; charset=utf-8", "Metodo no permitido".getBytes(StandardCharsets.UTF_8), null);
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/") || path.contains("..") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
                send(ex, 404, "application/json; charset=utf-8", "{\"error\":\"No encontrado\"}".getBytes(StandardCharsets.UTF_8), null);
                return;
            }
            String rel = path.equals("/") ? "index.html" : path.substring(1);
            String last = rel.substring(rel.lastIndexOf('/') + 1);
            boolean isAsset = last.contains(".");
            byte[] body = isAsset ? read(rel) : null;
            if (body == null && !isAsset) {
                rel = "index.html";
                body = read(rel);
            }
            if (body == null) {
                boolean missingUi = rel.equals("index.html");
                send(ex, 404, "text/plain; charset=utf-8", (missingUi
                        ? "Interfaz web no incluida en esta compilacion (ejecute scripts\\build-dist.ps1)"
                        : "No encontrado").getBytes(StandardCharsets.UTF_8), null);
                return;
            }
            String ext = rel.substring(rel.lastIndexOf('.') + 1);
            String cache = rel.startsWith("assets/") ? "public, max-age=31536000, immutable" : "no-cache";
            send(ex, 200, TYPES.getOrDefault(ext, "application/octet-stream"), body, cache);
        }
    }

    private static byte[] read(String rel) throws IOException {
        try (InputStream in = StaticFiles.class.getResourceAsStream(ROOT + rel)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body, String cache) throws IOException {
        var h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Content-Security-Policy",
                "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'");
        h.set("Cache-Control", cache == null ? "no-store" : cache);
        boolean head = ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(status, head || body.length == 0 ? -1 : body.length);
        if (!head && body.length > 0) {
            ex.getResponseBody().write(body);
        }
    }
}
