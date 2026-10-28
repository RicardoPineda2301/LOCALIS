package com.localis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localis.index.CodeChunk;
import com.localis.rag.Context;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Bug #0 de la corrida 1: el presupuesto de contexto debe llenarse por relevancia, no por orden alfabetico. */
class ContextTest {

    private static CodeChunk chunk(String path, int startLine, int lines, String lineText) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            sb.append(lineText).append(' ').append(i).append('\n');
        }
        return new CodeChunk(0, path, startLine, startLine + lines - 1, "block", null, sb.toString(), null);
    }

    private static List<String> paths(Context ctx) {
        return ctx.sources().stream().map(Context.SourceRef::path).toList();
    }

    @Test
    void theMostRelevantChunkSurvivesEvenIfItsPathSortsLast() {
        CodeChunk best = chunk("supabase/migrations/900_contrato.sql", 1, 20, "create function rescindir()");
        CodeChunk second = chunk("src/pagos/Cobro.java", 1, 20, "double total = subtotal * tasa;");
        CodeChunk noise = chunk("Claude outputs/notas.md", 1, 20, "notas sueltas de una sesion");
        int budget = Context.fromChunks(List.of(best), 100_000).text().length()
                + Context.fromChunks(List.of(second), 100_000).text().length() + 10;

        Context ctx = Context.fromChunks(List.of(best, second, noise), budget);

        assertEquals(List.of(second.path(), best.path()), paths(ctx), "entran los dos mas relevantes");
        assertFalse(ctx.text().contains("notas sueltas"), "el menos relevante se queda fuera");
        assertTrue(ctx.text().indexOf("src/pagos") < ctx.text().indexOf("supabase/"),
                "los elegidos se muestran ordenados por ruta");
    }

    @Test
    void aChunkThatDoesNotFitIsSkippedAndASmallerLaterOneIsStillAdded() {
        CodeChunk first = chunk("a/First.java", 1, 10, "linea corta");
        CodeChunk huge = chunk("b/Huge.java", 1, 400, "una linea bastante larga para llenar el contexto");
        CodeChunk small = chunk("c/Small.java", 1, 5, "otra linea corta");
        int budget = Context.fromChunks(List.of(first), 100_000).text().length()
                + Context.fromChunks(List.of(small), 100_000).text().length() + 10;

        Context ctx = Context.fromChunks(List.of(first, huge, small), budget);

        assertEquals(List.of("a/First.java", "c/Small.java"), paths(ctx));
        assertTrue(ctx.text().length() <= budget);
    }

    @Test
    void ifTheMostRelevantChunkAloneExceedsTheBudgetItIsCutByWholeLines() {
        CodeChunk huge = chunk("b/Huge.java", 101, 400, "una linea bastante larga para llenar el contexto");
        CodeChunk small = chunk("c/Small.java", 1, 5, "otra linea corta");

        Context ctx = Context.fromChunks(List.of(huge, small), 2000);

        assertEquals(1, ctx.sources().size());
        Context.SourceRef ref = ctx.sources().get(0);
        assertEquals("b/Huge.java", ref.path());
        assertEquals(101, ref.startLine());
        assertTrue(ref.endLine() < 500 && ref.endLine() > 101, "se recorta, pero conserva varias lineas");
        assertTrue(ctx.text().length() <= 2000, "respeta el presupuesto: " + ctx.text().length());
        assertTrue(ctx.text().contains("(lines 101-" + ref.endLine() + ")"), "la cabecera refleja el recorte");
        assertTrue(ctx.text().contains(ref.endLine() + "| "), "la ultima linea mostrada es la del rango");
        assertFalse(ctx.text().contains((ref.endLine() + 1) + "| "), "no se muestra nada fuera del rango");
        int[] shown = ctx.shownRanges().get("b/Huge.java").get(0);
        assertEquals(101, shown[0]);
        assertEquals(ref.endLine(), shown[1], "el rango visto coincide con lo recortado");
    }

    @Test
    void chunksThatFitAreKeptWholeAndNothingIsDroppedWhenThereIsRoom() {
        CodeChunk a = chunk("z/Last.java", 1, 3, "x");
        CodeChunk b = chunk("a/First.java", 1, 3, "y");

        Context ctx = Context.fromChunks(List.of(a, b), 100_000);

        assertEquals(List.of("a/First.java", "z/Last.java"), paths(ctx));
        assertEquals(List.of(1, 3), List.of(ctx.shownRanges().get("z/Last.java").get(0)[0],
                ctx.shownRanges().get("z/Last.java").get(0)[1]));
    }

    @Test
    void filesAreNumberedInDisplayOrderAndChunksOfTheSameFileShareTheNumber() {
        CodeChunk b1 = chunk("b/B.java", 1, 3, "x");
        CodeChunk b2 = chunk("b/B.java", 50, 3, "y");
        CodeChunk a = chunk("a/A.sql", 1, 3, "z");

        Context ctx = Context.fromChunks(List.of(b1, b2, a), 100_000);

        assertEquals(List.of("a/A.sql", "b/B.java"), ctx.files());
        assertTrue(ctx.text().contains("=== [1] FILE: a/A.sql (lines 1-3)"));
        assertTrue(ctx.text().contains("=== [2] FILE: b/B.java (lines 1-3)"));
        assertTrue(ctx.text().contains("=== [2] FILE: b/B.java (lines 50-52)"));
        assertFalse(ctx.text().contains("[3] FILE"));
        assertTrue(ctx.text().indexOf("[1] FILE") < ctx.text().indexOf("[2] FILE"), "misma numeracion que el orden");
        assertEquals(List.of("f.java"), Context.fromLines("f.java", List.of("a", "b"), 1, 2).files());
        assertTrue(Context.fromLines("f.java", List.of("a", "b"), 1, 2).text().startsWith("=== [1] FILE: f.java"));
    }
}
