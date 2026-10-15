package com.localis.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiempos de las llamadas al modelo hechas por el hilo actual (un analisis = un hilo). Sirve para ver donde se va el
 * tiempo: cargar el modelo, leer el prompt o generar la respuesta.
 */
public final class LlmStats {

    public record Call(String kind, int promptTokens, int genTokens, double loadSeconds, double promptSeconds,
            double genSeconds, double totalSeconds, boolean looped) {}

    private static final ThreadLocal<List<Call>> CALLS = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> TRUNCATED = new ThreadLocal<>();

    private LlmStats() {}

    /** Empieza a recoger en este hilo; cerrar el resultado deja de recoger. */
    public static AutoCloseable collect() {
        CALLS.set(new ArrayList<>());
        return CALLS::remove;
    }

    static void record(Call c) {
        List<Call> l = CALLS.get();
        if (l != null) {
            l.add(c);
        }
    }

    /** La ultima respuesta quedo cortada (tope de tokens o bucle de repeticion que no se pudo evitar). */
    static void markTruncated() {
        TRUNCATED.set(true);
    }

    /** True si alguna llamada de este hilo se corto por el tope de tokens desde la ultima vez; lo reinicia. */
    public static boolean consumeTruncated() {
        boolean t = Boolean.TRUE.equals(TRUNCATED.get());
        TRUNCATED.remove();
        return t;
    }

    /** Resumen de lo recogido hasta ahora en este hilo (vacio si no se recoge). */
    public static Map<String, Object> summary() {
        List<Call> l = CALLS.get();
        Map<String, Object> out = new LinkedHashMap<>();
        if (l == null) {
            return out;
        }
        List<Call> chats = l.stream().filter(c -> c.kind().equals("chat")).toList();
        int prompt = 0;
        int gen = 0;
        double load = 0;
        double ps = 0;
        double gs = 0;
        double total = 0;
        for (Call c : chats) {
            prompt += c.promptTokens();
            gen += c.genTokens();
            load += c.loadSeconds();
            ps += c.promptSeconds();
            gs += c.genSeconds();
            total += c.totalSeconds();
        }
        out.put("modelCalls", chats.size());
        out.put("promptTokens", prompt);
        out.put("generatedTokens", gen);
        out.put("loadSeconds", round(load));
        out.put("promptSeconds", round(ps));
        out.put("generationSeconds", round(gs));
        out.put("modelSeconds", round(total));
        out.put("tokensPerSecond", gs > 0 ? round(gen / gs) : 0);
        out.put("loopRetries", chats.stream().filter(Call::looped).count());
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
