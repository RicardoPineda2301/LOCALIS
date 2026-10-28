package com.localis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localis.TestSupport.FakeLlm;
import com.localis.index.Chunker;
import com.localis.index.CodeChunk;
import com.localis.index.IndexStore;
import com.localis.search.ChunkWeights;
import com.localis.search.Retriever;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChunkWeightsTest {

    private static CodeChunk chunk(String path, String kind, String symbol, String text) {
        return new CodeChunk(0, path, 1, 3, kind, symbol, text, null);
    }

    @Test
    void stalePathsAreDemoted() {
        assertTrue(ChunkWeights.isStalePath("docs/historico/plan.md"));
        assertTrue(ChunkWeights.isStalePath("app/_to_delete/Old.tsx"));
        assertTrue(ChunkWeights.isStalePath("notas/bitacora-2024.md"));
        assertFalse(ChunkWeights.isStalePath("src/old_but_live/Service.java"));
        assertFalse(ChunkWeights.isStalePath("src/History.java"));
        double[] w = ChunkWeights.compute(List.of(chunk("docs/historico/x.md", "block", null, "a")));
        assertEquals(0.5, w[0]);
    }

    @Test
    void identicalTextInSeveralPathsKeepsTheCanonicalCopy() {
        List<CodeChunk> chunks = List.of(
                chunk("Claude outputs/funciones.sql", "sql", "function f", "CREATE FUNCTION f() ..."),
                chunk("supabase/migrations/0090_f.sql", "sql", "function f", "CREATE FUNCTION f() ..."),
                chunk("docs/copia.sql", "sql", "function f", "CREATE FUNCTION f() ..."),
                chunk("supabase/migrations/0090_f.sql", "sql", "function g", "CREATE FUNCTION g() ..."));
        double[] w = ChunkWeights.compute(chunks);
        assertEquals(0.3, w[0], 1e-9);
        assertEquals(1.0, w[1], 1e-9, "la ruta canonica se conserva");
        assertEquals(0.3, w[2], 1e-9);
        assertEquals(1.0, w[3], 1e-9, "texto distinto no es duplicado");
    }

    @Test
    void onlyTheLatestMigrationOfAnObjectKeepsFullWeightAndTablesAreNotVersioned() {
        List<CodeChunk> chunks = List.of(
                chunk("supabase/migrations/0086_contratos.sql", "sql", "function public.rescindir", "v1"),
                chunk("supabase/migrations/0090_contratos.sql", "sql", "function public.rescindir", "v2"),
                chunk("supabase/migrations/0097_contratos.sql", "sql", "function PUBLIC.rescindir", "v3"),
                chunk("supabase/migrations/0086_contratos.sql", "sql", "alter table contratos", "add a"),
                chunk("supabase/migrations/0097_contratos.sql", "sql", "alter table contratos", "add b"),
                chunk("otro/migrations/0001.sql", "sql", "function public.rescindir", "otra carpeta"),
                chunk("src/rescindir.sql", "sql", "function public.rescindir", "fuera de migraciones"));
        double[] w = ChunkWeights.compute(chunks);
        assertEquals(0.3, w[0], 1e-9);
        assertEquals(0.3, w[1], 1e-9);
        assertEquals(1.0, w[2], 1e-9, "la migracion mas alta es la vigente (sin distinguir mayusculas)");
        assertEquals(1.0, w[3], 1e-9, "los ALTER TABLE son acumulativos");
        assertEquals(1.0, w[4], 1e-9);
        assertEquals(1.0, w[5], 1e-9, "otra carpeta de migraciones es otra historia");
        assertEquals(1.0, w[6], 1e-9, "fuera de migraciones no hay versiones");
    }

    @Test
    void theRetrieverRanksTheCurrentVersionAboveOlderOnes(@TempDir Path tmp) {
        FakeLlm llm = new FakeLlm();
        llm.embeddingsFail = true; // solo BM25, para que el orden dependa unicamente del peso
        try (IndexStore store = new IndexStore(tmp.resolve("index.db"))) {
            Chunker chunker = new Chunker();
            String body = "CREATE OR REPLACE FUNCTION rescindir_contrato(p_id int) RETURNS void AS $$\n"
                    + "BEGIN UPDATE contratos SET estado = 'rescindido' WHERE id = p_id; END;\n$$ LANGUAGE plpgsql;\n";
            for (String name : List.of("0086_x.sql", "0090_x.sql", "0097_x.sql")) {
                String path = "supabase/migrations/" + name;
                store.replaceFile(path, name, 3, false, chunker.chunk(path, body));
            }
            List<Retriever.Hit> hits = new Retriever(store, llm).search("rescindir contrato", 3);
            assertEquals(3, hits.size());
            assertEquals("supabase/migrations/0097_x.sql", hits.get(0).chunk().path());
            assertTrue(hits.get(0).score() > hits.get(1).score());
        }
    }
}
