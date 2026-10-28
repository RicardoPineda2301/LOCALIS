package com.localis.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exclusiones con la sintaxis de .gitignore, para no indexar lo que el cliente excluyo a proposito (a veces porque
 * contiene secretos). Fuentes, de menor a mayor prioridad: .git/info/exclude, el .gitignore de la raiz, los
 * .gitignore de cada subcarpeta (solo para lo que cuelga de ellas) y, por ultimo, .localisignore en la raiz, con la
 * misma sintaxis. Dentro de un fichero la ultima regla que coincide manda, y una regla con "!" vuelve a incluir.
 *
 * <p>Seguridad: los ficheros de reglas se leen sin seguir enlaces simbolicos y con limite de tamano, y los patrones
 * se comparan por componentes de ruta sin expresiones regulares, asi que un .gitignore hostil no puede colgar el
 * servidor ni hacerlo leer fuera del repositorio.
 */
public final class IgnoreRules {

    public static final String LOCALIS_IGNORE = ".localisignore";
    static final String GIT_IGNORE = ".gitignore";
    static final String GIT_EXCLUDE = ".git/info/exclude";
    public static final long MAX_RULE_FILE_BYTES = 256 * 1024;
    static final int MAX_RULE_LENGTH = 1024;

    /** Una linea de un fichero de reglas, ya interpretada. baseDir: carpeta (relativa, "" = raiz) donde vive. */
    record Rule(String[] segments, boolean negated, boolean dirOnly, boolean anchored, String baseDir) {}

    private final Path root;
    private final List<Rule> rootRules;
    private final List<Rule> localisRules;
    private final Map<String, List<Rule>> nestedRules = new ConcurrentHashMap<>();

    private IgnoreRules(Path root, List<Rule> rootRules, List<Rule> localisRules) {
        this.root = root;
        this.rootRules = rootRules;
        this.localisRules = localisRules;
    }

    /** Sin reglas: no excluye nada. */
    public static IgnoreRules none() {
        return new IgnoreRules(null, List.of(), List.of());
    }

    /** Carga las reglas de la raiz del repositorio; las de subcarpetas se leen cuando el escaneo llega a ellas. */
    public static IgnoreRules load(Path root) {
        List<Rule> base = new ArrayList<>(parse(readRules(root.resolve(GIT_EXCLUDE)), ""));
        base.addAll(parse(readRules(root.resolve(GIT_IGNORE)), ""));
        return new IgnoreRules(root, base, parse(readRules(root.resolve(LOCALIS_IGNORE)), ""));
    }

    /** Reglas en texto (una por linea), todas relativas a la raiz. Pensado para tests. */
    public static IgnoreRules of(String gitignoreText, String localisignoreText) {
        return new IgnoreRules(null, parse(gitignoreText, ""), parse(localisignoreText, ""));
    }

    /**
     * True si la ruta (relativa a la raiz, con '/') debe excluirse. No se comprueban las carpetas intermedias: quien
     * recorre el repositorio pregunta por cada carpeta antes de entrar y no entra en las excluidas.
     */
    public boolean isIgnored(String relativePath, boolean isDirectory) {
        if (relativePath.isEmpty()) {
            return false;
        }
        String[] components = relativePath.split("/");
        Boolean verdict = null;
        for (Rule r : rootRules) {
            Boolean v = apply(r, components, isDirectory);
            verdict = v != null ? v : verdict;
        }
        // .gitignore de cada carpeta intermedia, de la mas externa a la mas interna (la mas interna manda).
        StringBuilder dir = new StringBuilder();
        for (int i = 0; i < components.length - 1; i++) {
            if (i > 0) {
                dir.append('/');
            }
            dir.append(components[i]);
            for (Rule r : rulesOf(dir.toString())) {
                Boolean v = apply(r, components, isDirectory);
                verdict = v != null ? v : verdict;
            }
        }
        for (Rule r : localisRules) {
            Boolean v = apply(r, components, isDirectory);
            verdict = v != null ? v : verdict;
        }
        return verdict != null && verdict;
    }

    private List<Rule> rulesOf(String dir) {
        if (root == null) {
            return List.of();
        }
        return nestedRules.computeIfAbsent(dir, d -> parse(readRules(root.resolve(d).resolve(GIT_IGNORE)), d));
    }

    /** null si la regla no aplica; si aplica, true = excluir, false = volver a incluir. */
    private static Boolean apply(Rule r, String[] components, boolean isDirectory) {
        int start = 0;
        if (!r.baseDir().isEmpty()) {
            String[] base = r.baseDir().split("/");
            if (components.length <= base.length) {
                return null;
            }
            for (int i = 0; i < base.length; i++) {
                if (!base[i].equals(components[i])) {
                    return null;
                }
            }
            start = base.length;
        }
        boolean hit = false;
        if (r.anchored()) {
            // Coincide con la ruta completa o con una carpeta intermedia (si excluye la carpeta, excluye lo de dentro).
            for (int end = start + 1; end <= components.length && !hit; end++) {
                boolean endIsDir = end < components.length || isDirectory;
                if (!r.dirOnly() || endIsDir) {
                    hit = matchSegments(r.segments(), 0, components, start, end);
                }
            }
        } else {
            // Sin '/': un solo nombre, que puede ser el fichero o cualquiera de sus carpetas (bajo baseDir).
            for (int i = start; i < components.length && !hit; i++) {
                boolean isDir = i < components.length - 1 || isDirectory;
                if (!r.dirOnly() || isDir) {
                    hit = matchSegment(r.segments()[0], components[i]);
                }
            }
        }
        return hit ? Boolean.valueOf(!r.negated()) : null;
    }

    // ----- lectura y parseo -----

    private static String readRules(Path file) {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_RULE_FILE_BYTES) {
                return "";
            }
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException | SecurityException e) {
            return "";
        }
    }

    static List<Rule> parse(String text, String baseDir) {
        List<Rule> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (String raw : text.split("\\R")) {
            Rule r = parseLine(raw, baseDir);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    static Rule parseLine(String raw, String baseDir) {
        String line = stripTrailingSpaces(raw);
        if (line.isEmpty() || line.startsWith("#") || line.length() > MAX_RULE_LENGTH) {
            return null;
        }
        boolean negated = false;
        if (line.startsWith("!")) {
            negated = true;
            line = line.substring(1);
        } else if (line.startsWith("\\!") || line.startsWith("\\#")) {
            line = line.substring(1);
        }
        boolean dirOnly = false;
        if (line.endsWith("/")) {
            dirOnly = true;
            line = line.substring(0, line.length() - 1);
        }
        if (line.startsWith("**/")) {
            line = line.substring(3); // "**/foo" equivale a "foo" sin anclar... salvo que tenga mas barras
            if (!line.contains("/")) {
                return line.isEmpty() ? null : new Rule(new String[] {line}, negated, dirOnly, false, baseDir);
            }
            line = "**/" + line;
        }
        boolean anchored = line.contains("/");
        if (line.startsWith("/")) {
            line = line.substring(1);
        }
        if (line.isEmpty()) {
            return null;
        }
        String[] segments = line.split("/");
        for (String s : segments) {
            if (s.isEmpty()) {
                return null; // "a//b" o barras sueltas: regla malformada, se ignora
            }
        }
        return new Rule(segments, negated, dirOnly, anchored, baseDir);
    }

    private static String stripTrailingSpaces(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ' && !(end >= 2 && s.charAt(end - 2) == '\\')) {
            end--;
        }
        return s.substring(0, end).replace("\\ ", " ");
    }

    // ----- comparacion de patrones (sin regex) -----

    /** Compara los segmentos del patron con los componentes [from, to) de la ruta; "**" cubre cero o mas. */
    static boolean matchSegments(String[] pat, int pi, String[] comps, int from, int to) {
        while (pi < pat.length) {
            if (pat[pi].equals("**")) {
                if (pi == pat.length - 1) {
                    return true; // "a/**": todo lo que cuelgue
                }
                for (int skip = from; skip <= to; skip++) {
                    if (matchSegments(pat, pi + 1, comps, skip, to)) {
                        return true;
                    }
                }
                return false;
            }
            if (from >= to || !matchSegment(pat[pi], comps[from])) {
                return false;
            }
            pi++;
            from++;
        }
        return from == to;
    }

    /** Comodines de un solo componente: '*' y '?' no cruzan '/', '[...]' clases, '\' escapa. Lineal, sin regex. */
    static boolean matchSegment(String pat, String text) {
        int p = 0;
        int t = 0;
        int starP = -1;
        int starT = -1;
        while (t < text.length()) {
            if (p < pat.length()) {
                char c = pat.charAt(p);
                if (c == '*') {
                    while (p < pat.length() && pat.charAt(p) == '*') {
                        p++;
                    }
                    starP = p;
                    starT = t;
                    continue;
                }
                int consumed = matchOne(pat, p, text.charAt(t));
                if (consumed > 0) {
                    p += consumed;
                    t++;
                    continue;
                }
            }
            if (starP >= 0) {
                p = starP;
                t = ++starT;
                continue;
            }
            return false;
        }
        while (p < pat.length() && pat.charAt(p) == '*') {
            p++;
        }
        return p == pat.length();
    }

    /** Cuantos caracteres del patron (desde p) consume la letra ch; 0 si no coincide. */
    private static int matchOne(String pat, int p, char ch) {
        char c = pat.charAt(p);
        if (c == '?') {
            return 1;
        }
        if (c == '\\' && p + 1 < pat.length()) {
            return pat.charAt(p + 1) == ch ? 2 : 0;
        }
        if (c == '[') {
            int close = pat.indexOf(']', p + 2);
            if (close < 0) {
                return ch == '[' ? 1 : 0; // '[' sin cerrar: literal
            }
            boolean negate = pat.charAt(p + 1) == '!' || pat.charAt(p + 1) == '^';
            int i = negate ? p + 2 : p + 1;
            boolean in = false;
            while (i < close) {
                char lo = pat.charAt(i);
                if (i + 2 < close && pat.charAt(i + 1) == '-') {
                    char hi = pat.charAt(i + 2);
                    in |= ch >= lo && ch <= hi;
                    i += 3;
                } else {
                    in |= ch == lo;
                    i++;
                }
            }
            return in != negate ? close - p + 1 : 0;
        }
        return c == ch ? 1 : 0;
    }
}
