package com.localis.workspace;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Navegador de carpetas del equipo donde corre LOCALIS, para elegir el repositorio (solo lista carpetas). */
public final class FolderBrowser {

    private static final int MAX_ENTRIES = 3000;

    private FolderBrowser() {}

    public record Folder(String name, String path) {}

    /** Ruta vacia = nivel superior (unidades de disco y carpetas habituales). */
    public static Map<String, Object> list(String rawPath) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        if (rawPath == null || rawPath.isBlank()) {
            List<Folder> roots = new ArrayList<>();
            for (Path r : FileSystems.getDefault().getRootDirectories()) {
                if (Files.isReadable(r)) {
                    roots.add(new Folder(r.toString(), r.toString()));
                }
            }
            out.put("path", "");
            out.put("parent", null);
            out.put("entries", roots);
            out.put("places", places());
            return out;
        }
        Path dir;
        try {
            dir = Path.of(rawPath).toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException e) {
            throw new IllegalArgumentException("Ruta no valida: " + rawPath);
        }
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("No es una carpeta: " + dir);
        }
        List<Folder> entries = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                try {
                    if (entries.size() < MAX_ENTRIES && Files.isDirectory(p) && !Files.isHidden(p)
                            && Files.isReadable(p)) {
                        entries.add(new Folder(p.getFileName().toString(), p.toString()));
                    }
                } catch (IOException | SecurityException e) {
                    // carpeta inaccesible: se omite
                }
            }
        } catch (java.nio.file.AccessDeniedException e) {
            throw new IllegalArgumentException("Acceso denegado a: " + dir);
        }
        entries.sort(Comparator.comparing(f -> f.name().toLowerCase(Locale.ROOT)));
        out.put("path", dir.toString());
        out.put("parent", dir.getParent() == null ? "" : dir.getParent().toString());
        out.put("entries", entries);
        return out;
    }

    private static List<Folder> places() {
        List<Folder> out = new ArrayList<>();
        Path home = Path.of(System.getProperty("user.home"));
        String[][] candidates = {{"Inicio", ""}, {"Escritorio", "Desktop"}, {"Documentos", "Documents"},
            {"Descargas", "Downloads"}};
        for (String[] c : candidates) {
            Path p = c[1].isEmpty() ? home : home.resolve(c[1]);
            if (Files.isDirectory(p)) {
                out.add(new Folder(c[0], p.toString()));
            }
        }
        return out;
    }
}
