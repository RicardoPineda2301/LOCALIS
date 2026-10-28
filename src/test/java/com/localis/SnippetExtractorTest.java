package com.localis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localis.index.Chunker;
import com.localis.index.IndexStore;
import com.localis.index.RepositoryScanner;
import com.localis.rag.CitationVerifier.Citation;
import com.localis.rag.CitationVerifier.Status;
import com.localis.rag.SnippetExtractor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnippetExtractorTest {

    @TempDir
    Path tmp;

    Path repo;
    IndexStore store;
    RepositoryScanner scanner;

    @BeforeEach
    void setUp() throws Exception {
        repo = Files.createDirectories(tmp.resolve("repo"));
        store = new IndexStore(tmp.resolve("data/index.db"));
        scanner = new RepositoryScanner(repo);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private void index(String path, String content) throws Exception {
        Path f = repo.resolve(path);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
        store.replaceFile(path, "h", Chunker.splitLines(content).size(), false, new Chunker().chunk(path, content));
    }

    private static Citation cite(String path, int from, int to, Status status) {
        return new Citation("[" + path + ":" + from + "]", path, from, to, status);
    }

    private static String bigMethod(int bodyLines) {
        StringBuilder sb = new StringBuilder("public class Big {\n    public int run(int x) {\n");
        for (int i = 0; i < bodyLines; i++) {
            sb.append("        x = x + ").append(i).append(";\n");
        }
        return sb.append("        return x;\n    }\n}\n").toString();
    }

    @Test
    void returnsTheWholeEnclosingMethodFromTheFileWithTheCitedLineHighlighted() throws Exception {
        String path = "src/com/acme/billing/InvoiceCalculator.java";
        index(path, TestSupport.INVOICE);
        List<String> lines = Chunker.splitLines(TestSupport.INVOICE);
        int round = lines.indexOf(lines.stream().filter(l -> l.contains("Math.round")).findFirst().orElseThrow()) + 1;

        var snippets = SnippetExtractor.extract(List.of(cite(path, round, round, Status.VERIFIED)), store, scanner);

        assertEquals(1, snippets.size());
        var s = snippets.get(0);
        assertEquals("InvoiceCalculator.calculateInvoiceTotal", s.symbol());
        assertTrue(s.code().contains("public double calculateInvoiceTotal(double subtotal, int items) {"));
        assertTrue(s.code().contains("return Math.round(total / items) * items;"));
        assertFalse(s.code().contains("nextInvoiceNumber"), "solo esa funcion");
        assertEquals(List.of(round), s.highlight());
        assertEquals(s.endLine() - s.startLine() + 1, Chunker.splitLines(s.code()).size());
    }

    @Test
    void ignoresUnverifiedCitationsAndDeduplicatesCitationsOfTheSameFunction() throws Exception {
        String path = "src/com/acme/billing/InvoiceCalculator.java";
        index(path, TestSupport.INVOICE);
        var out = SnippetExtractor.extract(List.of(
                cite(path, 14, 14, Status.OUT_OF_RANGE),
                cite("Fantasma.java", 3, 3, Status.FILE_NOT_FOUND),
                cite(path, 14, 14, Status.VERIFIED),
                cite(path, 16, 16, Status.VERIFIED)), store, scanner);
        assertEquals(1, out.size());
        assertEquals(List.of(14, 16), out.get(0).highlight());
    }

    @Test
    void rejoinsLongMethodsIndexedInOverlappingWindows() throws Exception {
        index("src/Big.java", bigMethod(150));
        var out = SnippetExtractor.extract(List.of(cite("src/Big.java", 100, 100, Status.VERIFIED)), store, scanner);
        assertEquals(1, out.size());
        assertEquals(2, out.get(0).startLine(), "empieza en la firma del metodo");
        assertTrue(out.get(0).code().contains("public int run(int x) {") && out.get(0).code().contains("return x;"));
    }

    @Test
    void hugeMethodsFallBackToTheWindowAroundTheLine() throws Exception {
        index("src/Huge.java", bigMethod(400));
        var out = SnippetExtractor.extract(List.of(cite("src/Huge.java", 200, 200, Status.VERIFIED)), store, scanner);
        assertEquals(1, out.size());
        var s = out.get(0);
        assertTrue(s.endLine() - s.startLine() + 1 <= 60);
        assertTrue(s.startLine() <= 200 && 200 <= s.endLine());
    }

    @Test
    void nonJavaFilesUseTheBlockContainingTheLineAndLargeCitedRangesAreNotHighlighted() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 150; i++) {
            sb.append("SELECT ").append(i).append(";\n");
        }
        index("db/q.sql", sb.toString());
        var out = SnippetExtractor.extract(List.of(cite("db/q.sql", 70, 100, Status.VERIFIED)), store, scanner);
        assertEquals(1, out.size());
        assertTrue(out.get(0).code().contains("SELECT 70;"));
        assertEquals(List.of(), out.get(0).highlight());
    }

    private static com.localis.rag.Context.SourceRef shown(String path, int from, int to) {
        return new com.localis.rag.Context.SourceRef(path, from, to, null);
    }

    /** Bug 5c: con ventanas solapadas (1-60, 51-110) y la cita en la 55, debe salir la ventana que el modelo vio. */
    @Test
    void withOverlappingWindowsTheSnippetIsTheWindowTheModelActuallySaw() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 150; i++) {
            sb.append("SELECT ").append(i).append(";\n");
        }
        index("db/q.sql", sb.toString());
        var cite = List.of(cite("db/q.sql", 55, 55, Status.VERIFIED));

        var seen = SnippetExtractor.extract(cite, List.of(shown("db/q.sql", 51, 110)), store, scanner);
        assertEquals(1, seen.size());
        assertEquals(51, seen.get(0).startLine(), "la ventana vista, no la de centro mas cercano");
        assertEquals(110, seen.get(0).endLine());
        assertEquals(List.of(55), seen.get(0).highlight());

        var legacy = SnippetExtractor.extract(cite, store, scanner);
        assertEquals(1, legacy.get(0).startLine(), "sin fuentes se conserva el comportamiento anterior");
        assertEquals(60, legacy.get(0).endLine());

        var wholeFile = SnippetExtractor.extract(cite, List.of(shown("db/q.sql", 1, 150)), store, scanner);
        assertEquals(1, wholeFile.get(0).startLine(), "modo fichero: ningun bloque coincide y se usan todos");
        assertEquals(60, wholeFile.get(0).endLine());

        var otherFile = SnippetExtractor.extract(cite, List.of(shown("db/other.sql", 51, 110)), store, scanner);
        assertEquals(1, otherFile.get(0).startLine(), "una fuente de otro fichero no cuenta");
    }

    @Test
    void infersLinesMentionedWithoutTheCitationFormat() {
        var shown = List.of(shown("src/A.java", 1, 40));
        String answer = "El error está en la línea 31:\n```java\n31| double x = a + b;\n```\nRevisa también las líneas 12-14.";
        var cites = SnippetExtractor.inferFromText(answer, shown);
        assertEquals(List.of(12, 31), cites.stream().map(Citation::startLine).sorted().toList());
        assertEquals(14, cites.stream().filter(c -> c.startLine() == 12).findFirst().orElseThrow().endLine());
        assertTrue(cites.stream().allMatch(c -> c.path().equals("src/A.java") && c.verified()));
    }

    @Test
    void doesNotGuessWhenTheLineBelongsToSeveralFilesOrWasNeverShown() {
        var two = List.of(shown("src/A.java", 1, 40), shown("src/B.java", 20, 60));
        assertEquals(List.of(), SnippetExtractor.inferFromText("mira la línea 31", two));
        assertEquals(List.of(), SnippetExtractor.inferFromText("mira la línea 999", List.of(shown("src/A.java", 1, 40))));
        assertEquals(List.of(), SnippetExtractor.inferFromText("mira la línea 5", List.of()));
    }

    @Test
    void ignoresNumbersThatAreNotLineReferences() {
        var shown = List.of(shown("src/A.java", 1, 40));
        assertEquals(List.of(), SnippetExtractor.inferFromText("El método tiene 15 líneas y 3 variables", shown));
        assertEquals(List.of(), SnippetExtractor.inferFromText(null, shown));
    }

    @Test
    void capsTheNumberOfSnippets() throws Exception {
        List<Citation> cites = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            index("src/F" + i + ".java", "public class F" + i + " {\n    int m() {\n        return " + i + ";\n    }\n}\n");
            cites.add(cite("src/F" + i + ".java", 3, 3, Status.VERIFIED));
        }
        assertEquals(SnippetExtractor.MAX_SNIPPETS, SnippetExtractor.extract(cites, store, scanner).size());
    }
}
