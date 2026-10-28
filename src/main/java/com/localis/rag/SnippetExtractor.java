package com.localis.rag;

import com.localis.index.CodeChunk;
import com.localis.index.Chunker;
import com.localis.index.IndexStore;
import com.localis.index.RepositoryScanner;
import com.localis.rag.CitationVerifier.Citation;
import com.localis.rag.CitationVerifier.Status;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Para cada cita verificada devuelve el codigo completo de la funcion que contiene las lineas citadas, leido del propio
 * fichero (nunca del texto del modelo, que podria alterarlo), con las lineas citadas marcadas.
 */
public final class SnippetExtractor {

    public static final int MAX_SNIPPETS = 3;
    static final int MAX_FUNCTION_LINES = 200;
    private static final int MAX_HIGHLIGHT_SPAN = 15;

    /** code: texto literal de startLine..endLine; highlight: lineas citadas dentro del tramo. */
    public record Snippet(String path, int startLine, int endLine, String symbol, String code,
            List<Integer> highlight) {}

    private SnippetExtractor() {}

    private static final java.util.regex.Pattern ECHOED_LINE = java.util.regex.Pattern.compile("(?m)^[ \\t]*(\\d{1,5})\\|");
    private static final java.util.regex.Pattern LINE_WORD = java.util.regex.Pattern.compile(
            "(?i)\\b(?:l[ií]neas?|lines?|linhas?|ligne|zeile)\\s+(\\d{1,5})(?:\\s*(?:-|\u2013|a|to)\\s*(\\d{1,5}))?");
    private static final int MAX_INFERRED = 5;

    /**
     * Cuando el modelo senala lineas sin el formato [ruta:linea] ("la linea 31", "31| codigo"), las deduce. Solo se
     * acepta una linea si el modelo la tuvo delante y pertenece a un unico fichero; ante la duda no se adivina.
     */
    public static List<Citation> inferFromText(String answer, List<Context.SourceRef> shown) {
        List<Citation> out = new ArrayList<>();
        if (answer == null || shown == null || shown.isEmpty()) {
            return out;
        }
        List<int[]> mentioned = new ArrayList<>();
        var echoed = ECHOED_LINE.matcher(answer);
        while (echoed.find()) {
            mentioned.add(new int[] {Integer.parseInt(echoed.group(1)), Integer.parseInt(echoed.group(1))});
        }
        var words = LINE_WORD.matcher(answer);
        while (words.find()) {
            int from = Integer.parseInt(words.group(1));
            int to = words.group(2) == null ? from : Integer.parseInt(words.group(2));
            mentioned.add(new int[] {from, to >= from && to - from < MAX_HIGHLIGHT_SPAN ? to : from});
        }
        for (int[] m : mentioned) {
            List<String> owners = shown.stream().filter(s -> s.startLine() <= m[0] && m[1] <= s.endLine())
                    .map(Context.SourceRef::path).distinct().toList();
            if (owners.size() != 1) {
                continue;
            }
            boolean dup = out.stream().anyMatch(c -> c.path().equals(owners.get(0)) && c.startLine() == m[0]);
            if (!dup && out.size() < MAX_INFERRED) {
                out.add(new Citation("linea " + m[0], owners.get(0), m[0], m[1], Status.VERIFIED));
            }
        }
        return out;
    }

    public static List<Snippet> extract(List<Citation> citations, IndexStore store, RepositoryScanner scanner) {
        return extract(citations, List.of(), store, scanner);
    }

    /**
     * 'sources': los tramos que el modelo tuvo delante. Entre las ventanas solapadas que contienen una linea citada se
     * prefiere la que el modelo vio, para que "Codigo implicado" coincida con "Fuentes consultadas".
     */
    public static List<Snippet> extract(List<Citation> citations, List<Context.SourceRef> sources, IndexStore store,
            RepositoryScanner scanner) {
        Map<String, int[]> ranges = new LinkedHashMap<>();
        Map<String, String> symbols = new LinkedHashMap<>();
        Map<String, List<Integer>> highlights = new LinkedHashMap<>();
        for (Citation c : citations) {
            if (!c.verified()) {
                continue;
            }
            List<CodeChunk> chunks = store.chunksOfFile(c.path());
            int[] range = enclosingRange(chunks, c.startLine(), sources);
            if (range == null) {
                continue;
            }
            String key = c.path() + ":" + range[0] + "-" + range[1];
            if (!ranges.containsKey(key) && ranges.size() >= MAX_SNIPPETS) {
                continue;
            }
            ranges.putIfAbsent(key, range);
            symbols.putIfAbsent(key, symbolAt(chunks, c.startLine()));
            List<Integer> hl = highlights.computeIfAbsent(key, k -> new ArrayList<>());
            if (c.endLine() - c.startLine() < MAX_HIGHLIGHT_SPAN) {
                for (int l = c.startLine(); l <= c.endLine(); l++) {
                    if (l >= range[0] && l <= range[1] && !hl.contains(l)) {
                        hl.add(l);
                    }
                }
            }
        }
        List<Snippet> out = new ArrayList<>();
        Map<String, List<String>> fileCache = new LinkedHashMap<>();
        for (var e : ranges.entrySet()) {
            String path = e.getKey().substring(0, e.getKey().lastIndexOf(':'));
            List<String> lines = fileCache.computeIfAbsent(path, p -> readLines(scanner, p));
            int[] r = e.getValue();
            if (lines == null || r[1] > lines.size()) {
                continue;
            }
            List<Integer> hl = highlights.get(e.getKey());
            hl.sort(Comparator.naturalOrder());
            out.add(new Snippet(path, r[0], r[1], symbols.get(e.getKey()),
                    String.join("\n", lines.subList(r[0] - 1, r[1])), hl));
        }
        return out;
    }

    static int[] enclosingRange(List<CodeChunk> chunks, int line) {
        return enclosingRange(chunks, line, List.of());
    }

    /**
     * Funcion completa si el chunk es un metodo; si no (o si es enorme), el bloque que contiene la linea. Si varios
     * bloques contienen la linea (ventanas solapadas), se consideran primero los que el modelo vio. Un bloque "visto"
     * es el que empieza donde empieza un tramo mostrado del mismo fichero: Context.truncate puede acortar el final
     * de un bloque pero nunca mueve su inicio. Si ninguno coincide (p. ej. modo fichero completo), se usan todos.
     */
    static int[] enclosingRange(List<CodeChunk> chunks, int line, List<Context.SourceRef> sources) {
        List<CodeChunk> all = chunks.stream().filter(c -> c.startLine() <= line && line <= c.endLine()).toList();
        if (all.isEmpty()) {
            return null;
        }
        List<CodeChunk> seen = all.stream().filter(c -> sources.stream()
                .anyMatch(s -> s.path().equals(c.path()) && s.startLine() == c.startLine())).toList();
        List<CodeChunk> containing = seen.isEmpty() ? all : seen;
        CodeChunk method = containing.stream().filter(SnippetExtractor::isUnit).findFirst().orElse(null);
        if (method != null) {
            int[] merged = mergeWindows(chunks, method);
            if (merged[1] - merged[0] + 1 <= MAX_FUNCTION_LINES) {
                return merged;
            }
        }
        CodeChunk best = containing.stream()
                .min(Comparator.comparingDouble(c -> Math.abs((c.startLine() + c.endLine()) / 2.0 - line)))
                .orElseThrow();
        return new int[] {best.startLine(), best.endLine()};
    }

    /** Fragmento con unidad propia (metodo Java, objeto SQL...): todo lo que no es una ventana "block" suelta. */
    private static boolean isUnit(CodeChunk c) {
        return c.symbol() != null && !"block".equals(c.kind());
    }

    /** Un metodo largo se indexa en ventanas solapadas con el mismo simbolo: se vuelven a unir. */
    private static int[] mergeWindows(List<CodeChunk> chunks, CodeChunk seed) {
        List<CodeChunk> same = chunks.stream()
                .filter(c -> isUnit(c) && java.util.Objects.equals(c.symbol(), seed.symbol()))
                .sorted(Comparator.comparingInt(CodeChunk::startLine)).toList();
        int start = seed.startLine();
        int end = seed.endLine();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (CodeChunk c : same) {
                if (c.startLine() < start && c.endLine() >= start) {
                    start = c.startLine();
                    grew = true;
                }
                if (c.endLine() > end && c.startLine() <= end) {
                    end = c.endLine();
                    grew = true;
                }
            }
        }
        return new int[] {start, end};
    }

    private static String symbolAt(List<CodeChunk> chunks, int line) {
        return chunks.stream().filter(c -> isUnit(c) && c.startLine() <= line && line <= c.endLine())
                .map(CodeChunk::symbol).findFirst().orElse(null);
    }

    private static List<String> readLines(RepositoryScanner scanner, String path) {
        try {
            String text = RepositoryScanner.decode(scanner.readBytes(path));
            return text == null ? null : Chunker.splitLines(text);
        } catch (IOException | SecurityException e) {
            return null;
        }
    }
}
