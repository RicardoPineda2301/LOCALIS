package com.localis.rag;

import com.localis.index.CodeChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Codigo que se le muestra al modelo, con cada linea numerada, y los rangos exactos que ha visto. */
public record Context(String text, Map<String, List<int[]>> shownRanges, List<SourceRef> sources) {

    public record SourceRef(String path, int startLine, int endLine, String symbol) {}

    public static Context fromChunks(List<CodeChunk> chunks, int maxChars) {
        List<CodeChunk> ordered = new ArrayList<>(chunks);
        ordered.sort(Comparator.comparing(CodeChunk::path).thenComparingInt(CodeChunk::startLine));

        StringBuilder sb = new StringBuilder();
        Map<String, List<int[]>> shown = new LinkedHashMap<>();
        List<SourceRef> sources = new ArrayList<>();
        for (CodeChunk c : ordered) {
            String block = format(c);
            if (sb.length() + block.length() > maxChars && !sources.isEmpty()) {
                break;
            }
            sb.append(block);
            shown.computeIfAbsent(c.path(), k -> new ArrayList<>()).add(new int[] {c.startLine(), c.endLine()});
            sources.add(new SourceRef(c.path(), c.startLine(), c.endLine(), c.symbol()));
        }
        return new Context(sb.toString(), shown, sources);
    }

    /** Fichero completo (o un tramo de el) con numeracion de lineas. */
    public static Context fromLines(String path, List<String> lines, int from, int to) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== FILE: ").append(path).append(" (lines ").append(from).append('-').append(to).append(") ===\n");
        appendNumbered(sb, lines, 1, from, to);
        Map<String, List<int[]>> shown = new LinkedHashMap<>();
        shown.put(path, List.of(new int[] {from, to}));
        return new Context(sb.toString(), shown, List.of(new SourceRef(path, from, to, null)));
    }

    static String format(CodeChunk c) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== FILE: ").append(c.path()).append(" (lines ").append(c.startLine()).append('-')
                .append(c.endLine()).append(')');
        if (c.symbol() != null) {
            sb.append(" [").append(c.symbol()).append(']');
        }
        sb.append(" ===\n");
        appendNumbered(sb, List.of(c.text().split("\\R", -1)), c.startLine(), c.startLine(), c.endLine());
        sb.append('\n');
        return sb.toString();
    }

    /** 'firstLine' es el numero de linea de la primera entrada de 'lines'. */
    private static void appendNumbered(StringBuilder sb, List<String> lines, int firstLine, int from, int to) {
        for (int n = from; n <= to; n++) {
            int idx = n - firstLine;
            if (idx >= 0 && idx < lines.size()) {
                sb.append(n).append("| ").append(lines.get(idx)).append('\n');
            }
        }
    }
}
