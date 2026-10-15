package com.localis.search;

import com.localis.index.CodeChunk;
import com.localis.index.IndexStore;
import com.localis.llm.LlmClient;
import com.localis.llm.LlmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Busqueda hibrida: BM25 (palabras clave) + similitud coseno (embeddings), fusionadas con RRF. */
public final class Retriever {

    private static final Logger LOG = LoggerFactory.getLogger(Retriever.class);
    private static final int RRF_K = 60;
    private static final int CANDIDATES = 50;

    public record Hit(CodeChunk chunk, double score) {}

    private record Snapshot(long generation, List<CodeChunk> chunks, float[][] unitVectors,
            List<Map<String, Integer>> tf, int[] lengths, Map<String, Integer> df, double avgLength) {}

    private final IndexStore store;
    private final LlmClient llm;
    private volatile Snapshot snapshot;

    public Retriever(IndexStore store, LlmClient llm) {
        this.store = store;
        this.llm = llm;
    }

    public List<Hit> search(String query, int k) {
        Snapshot s = snapshot();
        if (s.chunks().isEmpty() || query.isBlank()) {
            return List.of();
        }
        Map<Integer, Double> fused = new HashMap<>();
        addRanks(fused, bm25Ranking(s, query));
        addRanks(fused, vectorRanking(s, query));

        return fused.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(k)
                .map(e -> new Hit(s.chunks().get(e.getKey()), e.getValue()))
                .toList();
    }

    private static void addRanks(Map<Integer, Double> fused, List<Integer> ranking) {
        for (int rank = 0; rank < ranking.size(); rank++) {
            fused.merge(ranking.get(rank), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
    }

    private List<Integer> bm25Ranking(Snapshot s, String query) {
        List<String> terms = Tokenizer.tokens(query).stream().distinct().toList();
        int n = s.chunks().size();
        double[] scores = new double[n];
        for (String term : terms) {
            Integer df = s.df().get(term);
            if (df == null) {
                continue;
            }
            double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
            for (int i = 0; i < n; i++) {
                Integer f = s.tf().get(i).get(term);
                if (f != null) {
                    double norm = f + 1.2 * (1 - 0.75 + 0.75 * s.lengths()[i] / s.avgLength());
                    scores[i] += idf * f * 2.2 / norm;
                }
            }
        }
        return topIndexes(scores);
    }

    private List<Integer> vectorRanking(Snapshot s, String query) {
        boolean anyVector = false;
        for (float[] v : s.unitVectors()) {
            if (v != null) {
                anyVector = true;
                break;
            }
        }
        if (!anyVector) {
            return List.of();
        }
        float[] q;
        try {
            q = unit(llm.embed(List.of(query)).get(0));
        } catch (LlmException e) {
            LOG.warn("No se pudo calcular el embedding de la consulta; se usan solo palabras clave: {}",
                    e.getMessage());
            return List.of();
        }
        double[] scores = new double[s.chunks().size()];
        for (int i = 0; i < scores.length; i++) {
            float[] v = s.unitVectors()[i];
            scores[i] = (v == null || v.length != q.length) ? 0 : dot(q, v);
        }
        return topIndexes(scores);
    }

    private static List<Integer> topIndexes(double[] scores) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] > 0) {
                idx.add(i);
            }
        }
        idx.sort((a, b) -> Double.compare(scores[b], scores[a]));
        return idx.size() > CANDIDATES ? idx.subList(0, CANDIDATES) : idx;
    }

    private Snapshot snapshot() {
        Snapshot s = snapshot;
        long gen = store.generation();
        if (s != null && s.generation() == gen) {
            return s;
        }
        synchronized (this) {
            if (snapshot != null && snapshot.generation() == gen) {
                return snapshot;
            }
            snapshot = build(gen);
            return snapshot;
        }
    }

    /** True si la estructura de busqueda esta construida y al dia (la proxima busqueda no tendra que esperarla). */
    public boolean isWarm() {
        Snapshot s = snapshot;
        return s != null && s.generation() == store.generation();
    }

    /**
     * Construye en segundo plano la estructura de busqueda. Con indices grandes cuesta varios segundos y, si no, los
     * paga la primera pregunta tras abrir o reindexar.
     */
    public void warmUp() {
        Thread.ofVirtual().name("localis-warmup").start(() -> {
            try {
                snapshot();
            } catch (RuntimeException e) {
                // el indice pudo cerrarse (cambio de repositorio): no hay nada que precalentar
            }
        });
    }

    private Snapshot build(long generation) {
        List<CodeChunk> chunks = store.allChunks();
        int n = chunks.size();
        float[][] vectors = new float[n][];
        List<Map<String, Integer>> tfs = new ArrayList<>(n);
        int[] lengths = new int[n];
        Map<String, Integer> df = new HashMap<>();
        long total = 0;
        for (int i = 0; i < n; i++) {
            CodeChunk c = chunks.get(i);
            vectors[i] = c.embedding() == null ? null : unit(c.embedding());
            Map<String, Integer> tf = Tokenizer.termFrequencies(
                    c.path() + " " + (c.symbol() == null ? "" : c.symbol()) + " " + c.text());
            tfs.add(tf);
            int len = tf.values().stream().mapToInt(Integer::intValue).sum();
            lengths[i] = Math.max(len, 1);
            total += lengths[i];
            tf.keySet().forEach(t -> df.merge(t, 1, Integer::sum));
        }
        return new Snapshot(generation, chunks, vectors, tfs, lengths, df, n == 0 ? 1 : (double) total / n);
    }

    private static float[] unit(float[] v) {
        double norm = 0;
        for (float f : v) {
            norm += f * f;
        }
        norm = Math.sqrt(norm);
        if (norm == 0) {
            return v;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / norm);
        }
        return out;
    }

    private static double dot(float[] a, float[] b) {
        double d = 0;
        for (int i = 0; i < a.length; i++) {
            d += a[i] * b[i];
        }
        return d;
    }
}
