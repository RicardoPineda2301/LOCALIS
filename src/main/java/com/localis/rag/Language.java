package com.localis.rag;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Detecta el idioma de un texto (heuristica local por palabras frecuentes, sin dependencias ni red). */
public final class Language {

    public static final String DEFAULT = "Spanish";

    private record Detection(String language, int score, int runnerUp) {}

    private static final Map<String, String> NAMES = Map.of("es", "Spanish", "en", "English", "pt", "Portuguese",
            "fr", "French", "de", "German", "it", "Italian");

    private static final Map<String, Set<String>> WORDS = new LinkedHashMap<>();

    static {
        WORDS.put("Spanish", words("el la los las un una unos unas que qué de del en y es por para con como cómo cuál "
                + "cual cuáles cuales se su sus al lo este esta esto estos estas hace hacer hay donde dónde cuando "
                + "cuándo porque pero sí también son está están ser fue tiene tienen puede pueden más muy sobre "
                + "entre explica explicar archivo fichero función"));
        WORDS.put("English", words("the is are was were of to in and or for with what how why which does do did this "
                + "that these those it its be been from by on at as not can could should would where when who there "
                + "their has have had will into about than then if but also file function"));
        WORDS.put("Portuguese", words("o os as um uma uns umas que de do da dos das em e é por para com como qual "
                + "quais seu sua seus suas ao este esta isto faz fazer há onde quando porque mas não também são "
                + "estão ser foi tem têm pode você arquivo função"));
        WORDS.put("French", words("le la les un une des du de en et est pour avec comme quel quelle quels quelles ce "
                + "cette ces que qui dans sur par au aux où quand pourquoi mais pas aussi sont être fait fonctionne "
                + "peut fichier fonction"));
        WORDS.put("German", words("der die das ein eine einen und ist sind für mit wie was welche welcher welches wo "
                + "wann warum aber nicht auch von zu im den dem des auf bei kann datei funktion"));
        WORDS.put("Italian", words("il lo la i gli le un uno una e è per con come quale quali che di del della in su "
                + "dove quando perché ma non anche sono essere fa fare può questo questa file funzione"));
    }

    private static final Pattern CODE_FENCE = Pattern.compile("```.*?```", Pattern.DOTALL);
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\n]*`");
    private static final Pattern CITATION = Pattern.compile("\\[[^\\]\n]*:\\d+[^\\]\n]*]");
    private static final Pattern NON_LETTERS = Pattern.compile("[^\\p{L}]+");

    private Language() {}

    private static Set<String> words(String s) {
        return Set.of(s.split(" "));
    }

    /** Idioma de la peticion: el del texto si se reconoce; si no, el del navegador; si no, {@link #DEFAULT}. */
    public static String resolve(String text, String acceptLanguageHeader) {
        String detected = detect(text, 1);
        if (detected != null) {
            return detected;
        }
        String fromBrowser = fromAcceptLanguage(acceptLanguageHeader);
        return fromBrowser != null ? fromBrowser : DEFAULT;
    }

    /** "es-ES,es;q=0.9,en;q=0.8" -> "Spanish"; null si no hay cabecera o el idioma no esta soportado. */
    public static String fromAcceptLanguage(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String first = header.split(",")[0].split(";")[0].trim().toLowerCase(Locale.ROOT);
        int dash = first.indexOf('-');
        return NAMES.get(dash > 0 ? first.substring(0, dash) : first);
    }

    /** Idioma del texto o null si no hay evidencia suficiente o hay empate. */
    public static String detect(String text, int minScore) {
        Detection d = analyze(text);
        return d != null && d.score() >= minScore && d.score() > d.runnerUp() ? d.language() : null;
    }

    /**
     * Idioma de una respuesta del modelo (prosa, sin codigo ni citas) solo si la evidencia es clara: sirve para
     * decidir si hay que pedir una reescritura, asi que ante la duda devuelve null.
     */
    public static String detectConfident(String answer) {
        Detection d = analyze(stripCode(answer));
        return d != null && d.score() >= 8 && d.score() >= 2 * d.runnerUp() ? d.language() : null;
    }

    private static String stripCode(String s) {
        String out = CODE_FENCE.matcher(s == null ? "" : s).replaceAll(" ");
        out = INLINE_CODE.matcher(out).replaceAll(" ");
        return CITATION.matcher(out).replaceAll(" ");
    }

    private static Detection analyze(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Map<String, Integer> score = new LinkedHashMap<>();
        WORDS.keySet().forEach(l -> score.put(l, 0));
        for (String w : NON_LETTERS.split(text.toLowerCase(Locale.ROOT))) {
            for (var e : WORDS.entrySet()) {
                if (e.getValue().contains(w)) {
                    score.merge(e.getKey(), 1, Integer::sum);
                }
            }
        }
        for (char c : text.toCharArray()) {
            switch (c) {
                case '¿', '¡', 'ñ', 'Ñ' -> score.merge("Spanish", 3, Integer::sum);
                case 'ã', 'õ' -> score.merge("Portuguese", 3, Integer::sum);
                case 'ß' -> score.merge("German", 3, Integer::sum);
                default -> { }
            }
        }
        String best = null;
        int bestScore = 0;
        int second = 0;
        for (var e : score.entrySet()) {
            if (e.getValue() > bestScore) {
                second = bestScore;
                bestScore = e.getValue();
                best = e.getKey();
            } else if (e.getValue() > second) {
                second = e.getValue();
            }
        }
        return best == null ? null : new Detection(best, bestScore, second);
    }
}
