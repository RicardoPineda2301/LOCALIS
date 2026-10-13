package com.localis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localis.TestSupport.FakeLlm;
import com.localis.index.Chunker;
import com.localis.index.IndexStore;
import com.localis.index.Indexer;
import com.localis.index.RepositoryScanner;
import com.localis.rag.AnswerEngine;
import com.localis.rag.AnswerResult;
import com.localis.rag.CitationVerifier;
import com.localis.rag.DiagnoseService;
import com.localis.rag.ExplainService;
import com.localis.rag.NotFoundException;
import com.localis.search.Retriever;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PipelineTest {

    @TempDir
    Path tmp;

    FakeLlm llm = new FakeLlm();
    Path repo;
    IndexStore store;
    Indexer indexer;
    Retriever retriever;
    AnswerEngine engine;
    int roundLine;

    @BeforeEach
    void setUp() throws Exception {
        repo = TestSupport.sampleRepo(Files.createDirectories(tmp.resolve("repo")));
        store = new IndexStore(tmp.resolve("data/index.db"));
        indexer = new Indexer(new RepositoryScanner(repo), store, llm);
        retriever = new Retriever(store, llm);
        engine = new AnswerEngine(llm, new CitationVerifier(store::filePaths,
                p -> store.file(p).map(IndexStore.FileInfo::lines).orElse(0)));
        roundLine = Chunker.splitLines(TestSupport.INVOICE).indexOf(
                Chunker.splitLines(TestSupport.INVOICE).stream().filter(l -> l.contains("Math.round")).findFirst()
                        .orElseThrow()) + 1;
        runIndex();
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private void runIndex() throws Exception {
        assertTrue(indexer.startAsync());
        indexer.awaitCompletion();
        assertEquals(Indexer.State.DONE, indexer.status().state(), indexer.status().message());
    }

    @Test
    void indexesSourcesAndIgnoresBuildDirectories() {
        assertEquals(java.util.Set.of("src/com/acme/billing/InvoiceCalculator.java",
                "src/com/acme/crm/CustomerService.java", "db/tax.sql"), store.filePaths());
        assertEquals(store.stats().chunks(), store.stats().embeddedChunks());
    }

    @Test
    void secondRunSkipsUnchangedFilesAndDetectsChangesAndDeletions() throws Exception {
        runIndex();
        assertEquals(3, indexer.status().filesSkipped());

        Files.writeString(repo.resolve("src/com/acme/crm/CustomerService.java"),
                TestSupport.CUSTOMER.replace("Hola", "Buenas"));
        Files.delete(repo.resolve("db/tax.sql"));
        runIndex();
        assertEquals(1, indexer.status().filesSkipped());
        assertFalse(store.filePaths().contains("db/tax.sql"));
        assertTrue(store.chunksOfFile("src/com/acme/crm/CustomerService.java").stream()
                .anyMatch(c -> c.text().contains("Buenas")));
    }

    @Test
    void retrievalFindsRelevantCodeWithEmbeddingsAndWithoutThem() {
        var hits = retriever.search("calculate invoice total tax", 3);
        assertEquals("src/com/acme/billing/InvoiceCalculator.java", hits.get(0).chunk().path());

        llm.embeddingsFail = true; // la busqueda debe degradar a solo palabras clave
        var lexical = retriever.search("calculateInvoiceTotal", 3);
        assertEquals("InvoiceCalculator.calculateInvoiceTotal", lexical.get(0).chunk().symbol());
    }

    @Test
    void indexingWithoutEmbeddingsStillWorksAndRetriesEmbeddingsLater() throws Exception {
        store.deleteFile("db/tax.sql");
        llm.embeddingsFail = true;
        Files.writeString(repo.resolve("db/new.sql"), "SELECT 1;\n");
        runIndex();
        assertFalse(indexer.status().embeddingsAvailable());
        assertTrue(store.file("db/new.sql").isPresent());
        assertFalse(store.file("db/new.sql").get().embedded());

        llm.embeddingsFail = false;
        runIndex();
        assertTrue(store.file("db/new.sql").get().embedded());
    }

    @Test
    void diagnoseReturnsVerifiedCitationAndSendsNumberedCode() {
        llm.chatHandler = msgs -> msgs.get(0).content().contains("comma-separated")
                ? "calculateInvoiceTotal, invoice, tax, round"
                : "Hipotesis 1 (alta): [src/com/acme/billing/InvoiceCalculator.java:" + roundLine
                        + "] redondea sobre items.";
        var diagnose = new DiagnoseService(llm, engine, retriever, 8, 16000);

        AnswerResult r = diagnose.diagnose("el sistema de facturacion genera mal el total de la factura");

        assertTrue(r.allCitationsVerified(), r.citations().toString());
        assertEquals(1, r.citations().size());
        assertTrue(r.warnings().isEmpty());
        String prompt = llm.chatCalls.get(llm.chatCalls.size() - 1).get(1).content();
        assertTrue(prompt.contains(roundLine + "|") && prompt.contains("Math.round(total / items)"));
    }

    @Test
    void hallucinatedCitationTriggersOneRetryAndIsReported() {
        llm.chatHandler = msgs -> msgs.get(0).content().contains("comma-separated")
                ? "invoice"
                : "Error en [src/com/acme/billing/InvoiceCalculator.java:313] y [Fantasma.java:7]";
        var diagnose = new DiagnoseService(llm, engine, retriever, 8, 16000);

        AnswerResult r = diagnose.diagnose("factura con total incorrecto");

        assertFalse(r.allCitationsVerified());
        assertEquals(2, r.citations().size());
        assertFalse(r.warnings().isEmpty());
        long answerCalls = llm.chatCalls.stream().filter(c -> !c.get(0).content().contains("comma-separated")).count();
        assertEquals(2, answerCalls, "un unico reintento de correccion");
    }

    @Test
    void retryCanRecoverBadCitations() {
        int[] n = {0};
        llm.chatHandler = msgs -> {
            if (msgs.get(0).content().contains("comma-separated")) {
                return "invoice";
            }
            return n[0]++ == 0 ? "Mira InvoiceCalculator.java:999"
                    : "Mira InvoiceCalculator.java:" + roundLine;
        };
        AnswerResult r = new DiagnoseService(llm, engine, retriever, 8, 16000).diagnose("factura total");
        assertTrue(r.allCitationsVerified());
    }

    @Test
    void diagnoseWithEmptyIndexExplainsInsteadOfCallingTheModel() {
        store.filePaths().forEach(store::deleteFile);
        AnswerResult r = new DiagnoseService(llm, engine, retriever, 8, 16000).diagnose("factura");
        assertTrue(r.answer().contains("No se encontro"));
        assertTrue(llm.chatCalls.stream().noneMatch(c -> c.get(0).content().contains("root-cause")));
    }

    @Test
    void explainFileUsesTheWholeFileAndRejectsUnknownPaths() throws Exception {
        llm.chatHandler = m -> "Calcula facturas. InvoiceCalculator.java:" + roundLine;
        var explain = new ExplainService(llm, engine, retriever, store, new RepositoryScanner(repo), 8, 16000);

        AnswerResult r = explain.explain("src\\com\\acme\\billing\\InvoiceCalculator.java", null);
        assertTrue(r.allCitationsVerified());
        assertThrows(NotFoundException.class, () -> explain.explain("../../etc/passwd", null));
        assertThrows(NotFoundException.class, () -> explain.explain("src/Nope.java", null));
        assertThrows(IllegalArgumentException.class, () -> explain.explain(null, "  "));
    }

    @Test
    void largeFilesAreSummarizedInPartsAndCombined() throws Exception {
        StringBuilder sb = new StringBuilder("public class Big {\n");
        for (int i = 0; i < 400; i++) {
            sb.append("    int field").append(i).append(" = ").append(i).append("; // comentario largo de relleno\n");
        }
        sb.append("}\n");
        Files.writeString(repo.resolve("src/Big.java"), sb.toString());
        runIndex();

        llm.chatHandler = m -> "Resumen. Big.java:10";
        var explain = new ExplainService(llm, engine, retriever, store, new RepositoryScanner(repo), 8, 4000);
        AnswerResult r = explain.explain("src/Big.java", "que hace");

        assertTrue(llm.chatCalls.size() > 2, "debe haber varias llamadas parciales + la final");
        assertTrue(r.allCitationsVerified());
        assertNotNull(r.warnings());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("por partes") || w.contains("primeras")));
        assertEquals(List.of(), r.citations().stream().filter(c -> !c.verified()).toList());
    }
}
