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
        if (path.toLowerCase(Locale.ROOT).endsWith(".java")) {
            List<CodeChunk> java = chunkJava(path, content, lines);
            if (!java.isEmpty()) {
                return java;
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
        int maxLines = "method".equals(kind) ? MAX_CALLABLE_LINES : WINDOW;
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
