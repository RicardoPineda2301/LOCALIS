package com.localis.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Tokeniza codigo y lenguaje natural: separa camelCase/snake_case, quita tildes y palabras vacias. */
public final class Tokenizer {

    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "with", "this", "that", "from", "are", "was", "not", "has", "have", "una", "uno",
            "los", "las", "del", "que", "por", "con", "para", "esta", "este", "como", "pero", "mas", "sus", "porque",
            "cuando", "donde", "falla", "fallando", "genera", "mal", "return", "public", "private", "static", "void",
            "new", "int", "string", "null", "true", "false", "import", "package", "class", "final");

    private Tokenizer() {}

    public static List<String> tokens(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        s = s.replaceAll("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])", " ");
        List<String> out = new ArrayList<>();
        for (String t : s.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (t.length() >= 2 && !STOPWORDS.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    public static Map<String, Integer> termFrequencies(String text) {
        Map<String, Integer> tf = new HashMap<>();
        for (String t : tokens(text)) {
            tf.merge(t, 1, Integer::sum);
        }
        return tf;
    }
}
