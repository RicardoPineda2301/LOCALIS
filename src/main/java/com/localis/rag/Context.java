package com.localis.rag;

import com.localis.index.CodeChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Codigo que se le muestra al modelo, con cada linea numerada, y los rangos exactos que ha visto. Cada fichero lleva
 * un numero ("=== [2] FILE: ... ===") para que el modelo cite como [2:45] en vez de copiar rutas; LOCALIS las repone.
 */
public record Context(String text, Map<String, List<int[]>> shownRanges, List<SourceRef> sources) {

    private static final Logger LOG = LoggerFactory.getLogger(Context.class);

    public record SourceRef(String path, int startLine, int endLine, String symbol) {}

    /** Ficheros mostrados en el orden en que se numeraron: el [1] es files().get(0). */
    public List<String> files() {
        return List.copyOf(shownRanges.keySet());
    }

    /**
     * Construye el contexto a partir de los fragmentos que devolvio el buscador, que vienen ordenados de mas a menos
     * relevante. El presupuesto de caracteres se llena en ESE orden: un fragmento que no cabe se salta y se prueba el
     * siguiente (puede ser mas corto). Solo si el fragmento mas relevante no cabe ni solo se recorta por lineas
     * enteras, para que los rangos vistos sigan siendo exactos. Al final los elegidos se ordenan por ruta y linea,
     * que es como conviene presentarselos al modelo; ese orden ya no decide quien entra y quien no.
     */
    public static Context fromChunks(List<CodeChunk> chunks, int maxChars) {
        List<CodeChunk> chosen = new ArrayList<>();
        int used = 0;
        int rank = 0;
        for (CodeChunk c : chunks) {
            rank++;
            int length = format(c).length();
            if (used + length <= maxChars) {
                chosen.add(c);
                used += length;
                LOG.info("contexto #{} entra: {}:{}-{} ({} chars, acumulado {}/{})", rank, c.path(), c.startLine(),
                        c.endLine(), length, used, maxChars);
            } else if (chosen.isEmpty()) {
                CodeChunk cut = truncate(c, maxChars);
                chosen.add(cut);
                used += format(cut).length();
                LOG.info("contexto #{} recortado: {}:{}-{} de {}-{} ({} de {} chars)", rank, c.path(),
                        cut.startLine(), cut.endLine(), c.startLine(), c.endLine(), used, length);
            } else {
                LOG.info("contexto #{} no cabe: {}:{}-{} ({} chars, libres {})", rank, c.path(), c.startLine(),
                        c.endLine(), length, maxChars - used);
            }
        }
        LOG.info("contexto: {} de {} fragmentos, {} de {} chars", chosen.size(), chunks.size(), used, maxChars);
        chosen.sort(Comparator.comparing(CodeChunk::path).thenComparingInt(CodeChunk::startLine));

        StringBuilder sb = new StringBuilder();
        Map<String, List<int[]>> shown = new LinkedHashMap<>();
        List<SourceRef> sources = new ArrayList<>();
        for (CodeChunk c : chosen) {
            List<int[]> ranges = shown.computeIfAbsent(c.path(), k -> new ArrayList<>());
            sb.append(format(c, shown.size()));
            ranges.add(new int[] {c.startLine(), c.endLine()});
            sources.add(new SourceRef(c.path(), c.startLine(), c.endLine(), c.symbol()));
        }
        return new Context(sb.toString(), shown, sources);
    }

    /** Recorta el fragmento a las primeras lineas que caben en maxChars (al menos una), con su cabecera. */
    static CodeChunk truncate(CodeChunk c, int maxChars) {
        List<String> lines = List.of(c.text().split("\\R", -1));
        int total = c.endLine() - c.startLine() + 1;
        int budget = maxChars - header(c, 1).length() - 1; // -1: linea en blanco que cierra el bloque
        int kept = 0;
        for (int i = 0; i < Math.min(total, lines.size()); i++) {
            int cost = String.valueOf(c.startLine() + i).length() + 2 + lines.get(i).length() + 1;
            if (kept > 0 && cost > budget) {
                break;
            }
            budget -= cost;
            kept++;
        }
        kept = Math.max(kept, 1);
        if (kept >= total) {
            return c;
        }
        String text = String.join("\n", lines.subList(0, kept));
        return new CodeChunk(c.id(), c.path(), c.startLine(), c.startLine() + kept - 1, c.kind(), c.symbol(), text,
                c.embedding());
    }

    /** Fichero completo (o un tramo de el) con numeracion de lineas. */
    public static Context fromLines(String path, List<String> lines, int from, int to) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== [1] FILE: ").append(path).append(" (lines ").append(from).append('-').append(to)
                .append(") ===\n");
        appendNumbered(sb, lines, 1, from, to);
        Map<String, List<int[]>> shown = new LinkedHashMap<>();
        shown.put(path, List.of(new int[] {from, to}));
        return new Context(sb.toString(), shown, List.of(new SourceRef(path, from, to, null)));
    }

    static String format(CodeChunk c) {
        return format(c, 1);
    }

    static String format(CodeChunk c, int fileNumber) {
        StringBuilder sb = new StringBuilder(header(c, fileNumber));
        appendNumbered(sb, List.of(c.text().split("\\R", -1)), c.startLine(), c.startLine(), c.endLine());
        sb.append('\n');
        return sb.toString();
    }

    private static String header(CodeChunk c, int fileNumber) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== [").append(fileNumber).append("] FILE: ").append(c.path()).append(" (lines ")
                .append(c.startLine()).append('-').append(c.endLine()).append(')');
        if (c.symbol() != null) {
            sb.append(" [").append(c.symbol()).append(']');
        }
        return sb.append(" ===\n").toString();
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
