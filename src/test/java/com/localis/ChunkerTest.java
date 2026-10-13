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

    @Test
    void emptyContentYieldsNoChunks() {
        assertTrue(chunker.chunk("a.sql", "").isEmpty());
        assertTrue(chunker.chunk("a.sql", "\n\n  \n").isEmpty());
    }
}
