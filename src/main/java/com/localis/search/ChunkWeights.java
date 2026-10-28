package com.localis.search;

import com.localis.index.CodeChunk;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Peso de cada fragmento para la busqueda (1 = normal, menos = se hunde en el ranking). Se calcula sobre todos los
 * fragmentos a la vez, porque depende de comparar ficheros entre si:
 * <ul>
 * <li>rutas de historico, copias o papelera (historico/, archive/, old/, _to_delete/, bitacora, changelog...): 0.5;</li>
 * <li>texto identico en varias rutas: se queda con la ruta canonica (src/, app/, supabase/, migrations...) y las
 * demas bajan a 0.3;</li>
 * <li>versiones de un mismo objeto SQL (funcion, vista, trigger, policy...) en una carpeta de migraciones: la del
 * fichero con el nombre mas alto es la vigente y las anteriores bajan a 0.3. Las tablas y los ALTER TABLE no se
 * tocan, porque son acumulativos y no se reemplazan.</li>
 * </ul>
 * Los pesos se multiplican entre si cuando aplica mas de una regla.
 */
public final class ChunkWeights {

    static final double STALE_PATH = 0.5;
    static final double DUPLICATE = 0.3;
    static final double OLD_VERSION = 0.3;

    private static final Set<String> STALE_DIRS = Set.of("historico", "histórico", "historic", "history", "archive",
            "archived", "archivo", "old", "backup", "backups", "bak", "_to_delete", "deprecated", "obsoleto", "tmp",
            "temp");
    private static final Set<String> STALE_NAME_PARTS = Set.of("bitacora", "bitácora", "changelog", "historico",
            "histórico");
    private static final Set<String> CANONICAL_TOP_DIRS = Set.of("src", "app", "lib", "supabase", "db", "sql",
            "migrations", "server", "backend", "frontend", "api", "packages");
    private static final Set<String> REPLACEABLE_SQL = Set.of("function", "procedure", "proc", "view", "trigger",
            "policy", "type", "package", "package body");

    private ChunkWeights() {}

    public static double[] compute(List<CodeChunk> chunks) {
        double[] w = new double[chunks.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = isStalePath(chunks.get(i).path()) ? STALE_PATH : 1.0;
        }
        demoteDuplicates(chunks, w);
        demoteOldVersions(chunks, w);
        return w;
    }

    public static boolean isStalePath(String path) {
        String[] parts = path.toLowerCase(Locale.ROOT).split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (STALE_DIRS.contains(parts[i])) {
                return true;
            }
        }
        String name = parts[parts.length - 1];
        return STALE_NAME_PARTS.stream().anyMatch(name::contains);
    }

    /** Menor es mejor: la copia que se conserva es la de la ruta mas "de codigo" y, a igualdad, la mas corta. */
    static int canonicalRank(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (isStalePath(path)) {
            return 3;
        }
        String top = lower.contains("/") ? lower.substring(0, lower.indexOf('/')) : "";
        if (CANONICAL_TOP_DIRS.contains(top) || lower.contains("/migrations/")) {
            return 0;
        }
        if (lower.startsWith("docs/") || lower.contains("claude") || lower.contains("output")
                || lower.contains("copia") || lower.contains("copy")) {
            return 2;
        }
        return 1;
    }

    private static void demoteDuplicates(List<CodeChunk> chunks, double[] w) {
        Map<String, List<Integer>> byText = new HashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            CodeChunk c = chunks.get(i);
            if (c.text().isBlank()) {
                continue;
            }
            byText.computeIfAbsent(c.text().strip(), k -> new ArrayList<>()).add(i);
        }
        for (List<Integer> same : byText.values()) {
            if (same.size() < 2 || same.stream().map(i -> chunks.get(i).path()).distinct().count() < 2) {
                continue;
            }
            int keep = same.stream().min((a, b) -> {
                String pa = chunks.get(a).path();
                String pb = chunks.get(b).path();
                int r = Integer.compare(canonicalRank(pa), canonicalRank(pb));
                if (r != 0) {
                    return r;
                }
                String da = migrationsDir(pa);
                if (da != null && da.equals(migrationsDir(pb))) {
                    return pb.compareTo(pa); // misma migracion repetida: se conserva la mas reciente
                }
                r = Integer.compare(pa.length(), pb.length());
                return r != 0 ? r : pa.compareTo(pb);
            }).orElseThrow();
            String keepPath = chunks.get(keep).path();
            for (int i : same) {
                if (!chunks.get(i).path().equals(keepPath)) {
                    w[i] *= DUPLICATE;
                }
            }
        }
    }

    private static void demoteOldVersions(List<CodeChunk> chunks, double[] w) {
        Map<String, List<Integer>> byObject = new HashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            CodeChunk c = chunks.get(i);
            String dir = migrationsDir(c.path());
            if (dir == null || !"sql".equals(c.kind()) || c.symbol() == null || !isReplaceable(c.symbol())) {
                continue;
            }
            byObject.computeIfAbsent(dir + "\n" + c.symbol().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(i);
        }
        for (List<Integer> versions : byObject.values()) {
            String latest = versions.stream().map(i -> chunks.get(i).path()).max(String::compareTo).orElseThrow();
            for (int i : versions) {
                if (!chunks.get(i).path().equals(latest)) {
                    w[i] *= OLD_VERSION;
                }
            }
        }
    }

    /** Carpeta de migraciones que contiene el fichero, o null si no esta en una. */
    static String migrationsDir(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        int at = lower.lastIndexOf("migration");
        if (at < 0) {
            return null;
        }
        int slash = lower.indexOf('/', at);
        return slash < 0 ? null : path.substring(0, slash);
    }

    private static boolean isReplaceable(String symbol) {
        String kind = symbol.toLowerCase(Locale.ROOT);
        int space = kind.lastIndexOf(' ');
        kind = space < 0 ? kind : kind.substring(0, space);
        return REPLACEABLE_SQL.contains(kind);
    }
}
