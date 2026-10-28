package com.localis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localis.index.Chunker;
import com.localis.index.CodeChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkerTest {

    private final Chunker chunker = new Chunker();

    @Test
    void javaIsSplitPerMethodWithExactLineNumbers() {
        List<CodeChunk> chunks = chunker.chunk("InvoiceCalculator.java", TestSupport.INVOICE);
        CodeChunk calc = chunks.stream().filter(c -> "InvoiceCalculator.calculateInvoiceTotal".equals(c.symbol()))
                .findFirst().orElseThrow();
        List<String> lines = Chunker.splitLines(TestSupport.INVOICE);

        // incluye el javadoc y termina en la llave de cierre del metodo
        assertTrue(lines.get(calc.startLine() - 1).contains("/**"));
        assertTrue(calc.text().contains("Math.round(total / items)"));
        assertEquals("    }", lines.get(calc.endLine() - 1));
        assertEquals(String.join("\n", lines.subList(calc.startLine() - 1, calc.endLine())), calc.text());
        assertTrue(chunks.stream().anyMatch(c -> "InvoiceCalculator.nextInvoiceNumber".equals(c.symbol())));
    }

    @Test
    void javaCodeBetweenMethodsIsNotLost() {
        String all = String.join("\n", chunker.chunk("InvoiceCalculator.java", TestSupport.INVOICE).stream()
                .map(CodeChunk::text).toList());
        assertTrue(all.contains("TAX_RATE = 0.21"));
        assertTrue(all.contains("import java.math.BigDecimal"));
    }

    @Test
    void unparsableJavaFallsBackToWindows() {
        List<CodeChunk> chunks = chunker.chunk("Broken.java", "public class { void ( {{{ \n int x = ;\n");
        assertFalse(chunks.isEmpty());
        assertEquals("block", chunks.get(0).kind());
    }

    @Test
    void otherLanguagesUseOverlappingWindowsCoveringEverything() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 150; i++) {
            sb.append("SELECT ").append(i).append(" FROM dual;\n");
        }
        List<CodeChunk> chunks = chunker.chunk("big.sql", sb.toString());
        assertEquals(1, chunks.get(0).startLine());
        assertEquals(150, chunks.get(chunks.size() - 1).endLine());
        for (int i = 1; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).startLine() <= chunks.get(i - 1).endLine(), "ventanas deben solaparse");
        }
    }

    private static final String PG_SQL = String.join("\n",
            "-- Esquema de contratos",
            "SET search_path = public;",
            "",
            "CREATE TABLE contratos (",
            "  id serial PRIMARY KEY,",
            "  estado text",
            ");",
            "",
            "-- Rescinde un contrato y deja rastro",
            "-- (version 3)",
            "CREATE OR REPLACE FUNCTION public.rescindir_contrato(p_id int)",
            "RETURNS void AS $$",
            "BEGIN",
            "  CREATE TEMP TABLE tmp_log (x int);",
            "  UPDATE contratos SET estado = 'rescindido' WHERE id = p_id;",
            "END;",
            "$$ LANGUAGE plpgsql;",
            "",
            "CREATE TRIGGER trg_contratos BEFORE UPDATE ON contratos",
            "  FOR EACH ROW EXECUTE FUNCTION rescindir_contrato();",
            "CREATE POLICY \"solo dueno\" ON contratos FOR SELECT USING (true);",
            "ALTER TABLE contratos ADD COLUMN fecha date;",
            "INSERT INTO contratos (estado) VALUES ('nuevo');",
            "");

    @Test
    void sqlIsSplitPerObjectWithTheObjectNameAsSymbol() {
        List<CodeChunk> chunks = chunker.chunk("supabase/migrations/0097_contratos.sql", PG_SQL);
        List<String> symbols = chunks.stream().map(CodeChunk::symbol).toList();
        assertEquals(java.util.Arrays.asList(null, "table contratos", "function public.rescindir_contrato",
                "trigger trg_contratos", "policy solo dueno", "alter table contratos", null), symbols);

        CodeChunk fn = chunks.get(2);
        assertEquals("sql", fn.kind());
        assertEquals(9, fn.startLine(), "incluye los comentarios pegados encima");
        assertEquals(17, fn.endLine(), "termina en el cierre $$ y no se corta por el CREATE TEMP de dentro");
        assertTrue(fn.text().contains("CREATE TEMP TABLE tmp_log"));

        assertEquals(1, chunks.get(0).startLine(), "la cabecera (comentario y SET) queda en una ventana");
        assertEquals(2, chunks.get(0).endLine());
        assertEquals("block", chunks.get(0).kind());
        assertEquals(4, chunks.get(1).startLine());
        assertEquals(7, chunks.get(1).endLine(), "sin la linea en blanco que separa objetos");
        assertEquals(23, chunks.get(6).startLine(), "el INSERT suelto del final tambien se indexa");
        assertEquals(23, chunks.get(6).endLine());
    }

    @Test
    void tSqlObjectsEndAtGoAndTempTablesDoNotStartAnObject() {
        String tsql = String.join("\n",
                "CREATE PROCEDURE [dbo].[sp_cierre]",
                "AS",
                "BEGIN",
                "  CREATE TABLE #tmp (x int);",
                "  SELECT 1;",
                "END",
                "GO",
                "",
                "CREATE VIEW dbo.v_saldos AS SELECT 1 AS saldo;",
                "GO",
                "");
        List<CodeChunk> chunks = chunker.chunk("db/cierre.sql", tsql);
        assertEquals(2, chunks.size());
        assertEquals("procedure dbo.sp_cierre", chunks.get(0).symbol());
        assertEquals(1, chunks.get(0).startLine());
        assertEquals(7, chunks.get(0).endLine(), "el GO cierra el lote");
        assertEquals("view dbo.v_saldos", chunks.get(1).symbol());
        assertEquals(9, chunks.get(1).startLine());
    }

    @Test
    void longSqlObjectsAreWindowedKeepingTheSymbol() {
        StringBuilder sb = new StringBuilder("CREATE OR REPLACE FUNCTION f() RETURNS void AS $$\nBEGIN\n");
        for (int i = 0; i < 200; i++) {
            sb.append("  PERFORM ").append(i).append(";\n");
        }
        sb.append("END;\n$$ LANGUAGE plpgsql;\n");
        List<CodeChunk> chunks = chunker.chunk("db/f.sql", sb.toString());
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(c -> "function f".equals(c.symbol()) && "sql".equals(c.kind())));
        assertEquals(1, chunks.get(0).startLine());
        assertEquals(204, chunks.get(chunks.size() - 1).endLine());
    }

    @Test
    void sqlWithoutObjectsStillUsesWindows() {
        List<CodeChunk> chunks = chunker.chunk("q.sql", "SELECT 1;\nSELECT 2;\n");
        assertEquals(1, chunks.size());
        assertEquals("block", chunks.get(0).kind());
    }

    @Test
    void emptyContentYieldsNoChunks() {
        assertTrue(chunker.chunk("a.sql", "").isEmpty());
        assertTrue(chunker.chunk("a.sql", "\n\n  \n").isEmpty());
    }
}
