package com.localis.index;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Divide un fichero en fragmentos. Java se divide por metodo/constructor (con su javadoc);
 * el resto de lenguajes, en ventanas solapadas de lineas.
 */
public final class Chunker {

    static final int WINDOW = 60;
    static final int OVERLAP = 10;
    static final int MAX_CALLABLE_LINES = 120;

    private record Range(int start, int end, String symbol) {}

    public List<CodeChunk> chunk(String path, String content) {
        List<String> lines = splitLines(content);
        if (lines.isEmpty()) {
            return List.of();
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) {
            List<CodeChunk> java = chunkJava(path, content, lines);
            if (!java.isEmpty()) {
                return java;
            }
        }
        if (lower.endsWith(".sql")) {
            List<CodeChunk> sql = chunkSql(path, lines);
            if (!sql.isEmpty()) {
                return sql;
            }
        }
        List<CodeChunk> out = new ArrayList<>();
        windows(out, path, lines, 1, lines.size(), "block", null);
        return out;
    }

    public static List<String> splitLines(String content) {
        List<String> lines = new ArrayList<>(List.of(content.split("\\R", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private List<CodeChunk> chunkJava(String path, String content, List<String> lines) {
        ParseResult<CompilationUnit> result;
        try {
            result = new JavaParser(new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)).parse(content);
        } catch (RuntimeException e) {
            return List.of();
        }
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            return List.of();
        }
        List<Range> callables = new ArrayList<>();
        for (CallableDeclaration<?> c : result.getResult().get().findAll(CallableDeclaration.class)) {
            if (c.findAncestor(CallableDeclaration.class).isPresent() || c.getRange().isEmpty()) {
                continue;
            }
            int start = c.getRange().get().begin.line;
            if (c.getComment().isPresent() && c.getComment().get().getRange().isPresent()) {
                start = Math.min(start, c.getComment().get().getRange().get().begin.line);
            }
            callables.add(new Range(start, c.getRange().get().end.line, symbolOf(c)));
        }
        if (callables.isEmpty()) {
            return List.of();
        }
        callables.sort(Comparator.comparingInt(Range::start));

        List<CodeChunk> out = new ArrayList<>();
        int cursor = 1;
        for (Range r : callables) {
            int start = Math.max(r.start(), cursor);
            if (start > r.end() || r.end() > lines.size()) {
                continue;
            }
            // Todo lo que queda entre metodos (campos, imports, clases internas) tambien se indexa.
            if (start > cursor) {
                windows(out, path, lines, cursor, start - 1, "block", null);
            }
            windows(out, path, lines, start, r.end(), "method", r.symbol());
            cursor = r.end() + 1;
        }
        if (cursor <= lines.size()) {
            windows(out, path, lines, cursor, lines.size(), "block", null);
        }
        return out;
    }

    private static final String SQL_NAME_PART = "(?:\"[^\"]*\"|\\[[^\\]]*\\]|`[^`]*`|[\\w#]+)";

    /**
     * Inicio de un objeto SQL de nivel superior: CREATE [OR REPLACE] [TEMP|TEMPORARY] [UNIQUE] [MATERIALIZED]
     * FUNCTION|PROCEDURE|PROC|VIEW|TABLE|TRIGGER|POLICY|INDEX|TYPE|SEQUENCE|SCHEMA nombre, o ALTER TABLE nombre.
     * Vale para Postgres, T-SQL, MySQL y Oracle sin entender el dialecto: solo mira la cabecera de cada sentencia.
     */
    static final Pattern SQL_OBJECT = Pattern.compile(
            "^\\s*(?:CREATE(?:\\s+OR\\s+REPLACE)?(?:\\s+(?:TEMP|TEMPORARY|UNIQUE|MATERIALIZED|DEFINER\\s*=\\s*\\S+))*"
            + "\\s+(FUNCTION|PROCEDURE|PROC|VIEW|TABLE|TRIGGER|POLICY|INDEX|TYPE|SEQUENCE|SCHEMA|PACKAGE(?:\\s+BODY)?)"
            + "|ALTER\\s+(TABLE))(?:\\s+IF\\s+NOT\\s+EXISTS|\\s+IF\\s+EXISTS|\\s+ONLY|\\s+CONCURRENTLY)*"
            + "\\s+(" + SQL_NAME_PART + "(?:\\." + SQL_NAME_PART + ")*)",
            Pattern.CASE_INSENSITIVE);

    /** Separador de lotes de T-SQL: cierra el objeto en curso aunque no venga otro CREATE detras. */
    private static final Pattern SQL_BATCH_END = Pattern.compile("^\\s*GO\\s*;?\\s*$", Pattern.CASE_INSENSITIVE);

    /** Objetos sin cuerpo: terminan en el primer ';' fuera de parentesis. Los demas (funciones, triggers...) tienen cuerpo. */
    private static final java.util.Set<String> SQL_SIMPLE_KINDS = java.util.Set.of(
            "VIEW", "TABLE", "POLICY", "INDEX", "TYPE", "SEQUENCE", "SCHEMA");

    private static final Pattern DOLLAR_QUOTE = Pattern.compile("\\$\\w*\\$");

    /**
     * Un fragmento por objeto SQL (funcion, procedimiento, vista, tabla, trigger, policy...), con el nombre del
     * objeto como simbolo y kind "sql". Un objeto empieza en su CREATE/ALTER (con los comentarios "--" pegados
     * encima) y termina: en el ';' que cierra la sentencia si no tiene cuerpo; en el ';' que sigue al cierre $$ si
     * es una funcion de Postgres; en el GO si es T-SQL; o justo antes del siguiente objeto. Lo que no pertenece a
     * ningun objeto (SET, INSERT sueltos, comentarios de cabecera) va en ventanas como antes. Objetos muy largos se
     * parten en ventanas que conservan el simbolo, igual que un metodo Java largo. Vacio si no hay ningun objeto.
     */
    private List<CodeChunk> chunkSql(String path, List<String> lines) {
        List<Range> objects = new ArrayList<>();
        int openStart = -1;
        String openSymbol = null;
        boolean openSimple = false;
        boolean openSawBody = false; // ya paso por un $$...$$: el proximo ';' fuera de el cierra la funcion
        int parens = 0;
        boolean inDollarQuote = false; // cuerpo $$ ... $$ de Postgres: un CREATE ahi dentro no es un objeto nuevo
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher m = inDollarQuote ? null : SQL_OBJECT.matcher(line);
            if (m != null && m.find() && !isTemporary(m)) {
                int start = commentBlockStart(lines, i + 1);
                if (openStart >= 0) {
                    objects.add(new Range(openStart, lastNonBlank(lines, openStart, start), openSymbol));
                }
                String kind = (m.group(1) != null ? m.group(1) : "ALTER " + m.group(2)).toUpperCase(Locale.ROOT)
                        .replaceAll("\\s+", " ");
                openStart = start;
                openSymbol = kind.toLowerCase(Locale.ROOT) + " " + cleanSqlName(m.group(3));
                openSimple = SQL_SIMPLE_KINDS.contains(kind) || m.group(2) != null;
                openSawBody = false;
                parens = 0;
            } else if (!inDollarQuote && SQL_BATCH_END.matcher(line).matches()) {
                if (openStart >= 0) {
                    objects.add(new Range(openStart, i + 1, openSymbol));
                    openStart = -1;
                } else if (!objects.isEmpty() && objects.get(objects.size() - 1).end() == i) {
                    // el GO que sigue a un objeto ya cerrado por ';' se queda con ese objeto
                    Range last = objects.remove(objects.size() - 1);
                    objects.add(new Range(last.start(), i + 1, last.symbol()));
                }
                continue;
            }
            String code = stripLineComment(line);
            boolean wasInDollar = inDollarQuote;
            if (countDollarQuotes(line) % 2 == 1) {
                inDollarQuote = !inDollarQuote; // se sigue aunque no haya objeto abierto (bloques DO $$ ... $$)
            }
            if (openStart < 0) {
                continue;
            }
            if (wasInDollar) {
                openSawBody = true;
                if (inDollarQuote) {
                    continue; // sigue dentro del cuerpo
                }
                code = code.substring(code.lastIndexOf('$') + 1); // lo que queda tras el cierre: "LANGUAGE plpgsql;"
            } else if (inDollarQuote) {
                openSawBody = true;
                code = code.substring(0, Math.max(code.indexOf('$'), 0));
            }
            parens += count(code, '(') - count(code, ')');
            boolean endsStatement = code.trim().endsWith(";") && parens <= 0;
            if (endsStatement && (openSimple || openSawBody)) {
                objects.add(new Range(openStart, i + 1, openSymbol));
                openStart = -1;
            }
        }
        if (openStart >= 0) {
            objects.add(new Range(openStart, lines.size(), openSymbol));
        }
        if (objects.isEmpty()) {
            return List.of();
        }
        List<CodeChunk> out = new ArrayList<>();
        int cursor = 1;
        for (Range r : objects) {
            int start = Math.max(r.start(), cursor);
            if (start > r.end()) {
                continue;
            }
            if (start > cursor) {
                // hueco entre objetos (SET, INSERT, comentarios sueltos), sin las lineas en blanco del final
                windows(out, path, lines, cursor, lastNonBlank(lines, cursor, start), "block", null);
            }
            windows(out, path, lines, start, r.end(), "sql", r.symbol());
            cursor = r.end() + 1;
        }
        if (cursor <= lines.size()) {
            windows(out, path, lines, cursor, lines.size(), "block", null);
        }
        return out;
    }

    /** Linea (1-based) donde empieza el bloque de comentarios "--" pegado justo encima de la linea dada. */
    private static int commentBlockStart(List<String> lines, int line) {
        int start = line;
        while (start > 1 && lines.get(start - 2).trim().startsWith("--")) {
            start--;
        }
        return start;
    }

    /** Tablas temporales (CREATE TEMP TABLE, #tmp de T-SQL) viven dentro de otro objeto: no abren uno nuevo. */
    private static boolean isTemporary(Matcher m) {
        String head = m.group().substring(0, m.start(3) - m.start()).toUpperCase(Locale.ROOT);
        return head.contains(" TEMP") || m.group(3).startsWith("#");
    }

    private static String stripLineComment(String line) {
        int at = line.indexOf("--");
        return at < 0 ? line : line.substring(0, at);
    }

    private static int count(String s, char ch) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ch) {
                n++;
            }
        }
        return n;
    }

    private static int countDollarQuotes(String line) {
        Matcher m = DOLLAR_QUOTE.matcher(line);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** Ultima linea no vacia (1-based) antes de 'nextStart' (1-based, inicio del siguiente objeto), sin bajar de 'from'. */
    private static int lastNonBlank(List<String> lines, int from, int nextStart) {
        int end = nextStart - 1;
        while (end > from && lines.get(end - 1).isBlank()) {
            end--;
        }
        return Math.max(end, from);
    }

    private static String cleanSqlName(String raw) {
        String name = raw.replace("[", "").replace("]", "").replace("\"", "").replace("`", "");
        int paren = name.indexOf('(');
        return paren > 0 ? name.substring(0, paren) : name;
    }

    private static String symbolOf(CallableDeclaration<?> c) {
        List<String> parts = new ArrayList<>();
        parts.add(c.getNameAsString());
        Node n = c;
        while (n.getParentNode().isPresent()) {
            n = n.getParentNode().get();
            if (n instanceof TypeDeclaration<?> t) {
                parts.add(0, t.getNameAsString());
            }
        }
        return String.join(".", parts);
    }

    /** Anade [from, to] como uno o varios chunks; los tramos largos se parten en ventanas. */
    private void windows(List<CodeChunk> out, String path, List<String> lines, int from, int to, String kind,
            String symbol) {
        int maxLines = "block".equals(kind) ? WINDOW : MAX_CALLABLE_LINES;
        int window = Math.min(WINDOW, maxLines);
        if (to - from + 1 <= maxLines) {
            add(out, path, lines, from, to, kind, symbol);
            return;
        }
        int step = window - OVERLAP;
        for (int s = from; s <= to; s += step) {
            int e = Math.min(s + window - 1, to);
            add(out, path, lines, s, e, kind, symbol);
            if (e == to) {
                break;
            }
        }
    }

    private void add(List<CodeChunk> out, String path, List<String> lines, int from, int to, String kind,
            String symbol) {
        List<String> slice = lines.subList(from - 1, to);
        if (slice.stream().allMatch(String::isBlank)) {
            return;
        }
        out.add(new CodeChunk(0, path, from, to, kind, symbol, String.join("\n", slice), null));
    }
}
