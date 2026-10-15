package com.localis.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comprueba cada cita "archivo:linea" que escribe el modelo: el archivo debe existir en el indice,
 * las lineas deben existir y, ademas, deben ser lineas que el modelo realmente tuvo delante.
 */
public final class CitationVerifier {

    public enum Status { VERIFIED, FILE_NOT_FOUND, AMBIGUOUS_FILE, OUT_OF_RANGE, NOT_IN_CONTEXT }

    public record Citation(String raw, String path, int startLine, int endLine, Status status) {
        public boolean verified() {
            return status == Status.VERIFIED;
        }
    }

    private static final Pattern CITATION = Pattern.compile(
            "(?<![\\w/.\\\\-])((?:[\\w.\\-]+[/\\\\])*[\\w.\\-]+\\.[A-Za-z][A-Za-z0-9]{0,7}):L?(\\d+)(?:\\s*[-\u2013]\\s*L?(\\d+))?");

    private final Supplier<Set<String>> indexedPaths;
    private final ToIntFunction<String> lineCount;

    public CitationVerifier(Supplier<Set<String>> indexedPaths, ToIntFunction<String> lineCount) {
        this.indexedPaths = indexedPaths;
        this.lineCount = lineCount;
    }

    public List<Citation> verify(String answer, Map<String, List<int[]>> shown) {
        Set<String> paths = indexedPaths.get();
        Map<String, Citation> out = new LinkedHashMap<>();
        Matcher m = CITATION.matcher(answer);
        while (m.find()) {
            String raw = m.group();
            if (out.containsKey(raw)) {
                continue;
            }
            int start = Integer.parseInt(m.group(2));
            int end = m.group(3) == null ? start : Integer.parseInt(m.group(3));
            out.put(raw, check(paths, raw, m.group(1).replace('\\', '/'), start, end, shown));
        }
        return new ArrayList<>(out.values());
    }

    private Citation check(Set<String> paths, String raw, String cited, int start, int end,
            Map<String, List<int[]>> shown) {
        List<String> matches = new ArrayList<>();
        if (paths.contains(cited)) {
            matches.add(cited);
        } else {
            for (String p : paths) {
                if (p.endsWith("/" + cited)) {
                    matches.add(p);
                }
            }
        }
        if (matches.isEmpty()) {
            return new Citation(raw, cited, start, end, Status.FILE_NOT_FOUND);
        }
        if (matches.size() > 1) {
            // Un nombre suelto ("queries.sql") puede coincidir con varios ficheros del repo, pero solo cuenta como
            // cita ambigua si el modelo vio mas de uno: si solo tuvo delante uno de ellos, es ese.
            List<String> seen = matches.stream().filter(p -> shown.containsKey(p)).toList();
            if (seen.size() != 1) {
                return new Citation(raw, cited, start, end, Status.AMBIGUOUS_FILE);
            }
            matches = seen;
        }
        String path = matches.get(0);
        if (start < 1 || end < start || end > lineCount.applyAsInt(path)) {
            return new Citation(raw, path, start, end, Status.OUT_OF_RANGE);
        }
        return new Citation(raw, path, start, end,
                covered(shown.getOrDefault(path, List.of()), start, end) ? Status.VERIFIED : Status.NOT_IN_CONTEXT);
    }

    /** True si [start, end] cabe entero en la union de los rangos mostrados. */
    static boolean covered(List<int[]> ranges, int start, int end) {
        List<int[]> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingInt(r -> r[0]));
        int reach = -1;
        int from = -1;
        for (int[] r : sorted) {
            if (reach < 0 || r[0] > reach + 1) {
                from = r[0];
                reach = r[1];
            } else {
                reach = Math.max(reach, r[1]);
            }
            if (from <= start && end <= reach) {
                return true;
            }
        }
        return false;
    }
}
