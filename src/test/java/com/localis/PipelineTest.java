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
    void binaryFilesAreReportedAsBinaryNotAsUnchangedAndOmissionsReachTheStatus() throws Exception {
        Files.write(repo.resolve("db/dump.sql"), new byte[] {0x53, 0x51, 0x4c, 0, 0, 1, 2, 3});
        Files.write(repo.resolve("logo.png"), new byte[] {1, 2, 3});
        runIndex();

        Indexer.Status s = indexer.status();
        assertEquals(1, s.filesBinary());
        assertEquals(3, s.filesSkipped(), "los tres fuentes sin cambios; el binario no cuenta como 'sin cambios'");
        assertEquals(0, s.filesFailed());
        assertEquals(java.util.Map.of("png", 1), s.omittedByExtension());
        assertEquals(0, s.omittedTooLarge());
        assertFalse(store.filePaths().contains("db/dump.sql"));
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
    void warmUpBuildsTheSearchStructureInTheBackgroundAndReindexingInvalidatesIt() throws Exception {
        assertFalse(retriever.isWarm(), "recien indexado: la estructura aun no se ha pedido");
        retriever.warmUp();
        long end = System.currentTimeMillis() + 5000;
        while (!retriever.isWarm() && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }
        assertTrue(retriever.isWarm());

        Files.writeString(repo.resolve("db/otra.sql"), "SELECT 2;\n");
        runIndex();
        assertFalse(retriever.isWarm(), "un cambio en el indice deja la estructura obsoleta");
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

    private static boolean isAnswerCall(List<com.localis.llm.LlmClient.Message> m) {
        return !m.get(0).content().contains("comma-separated");
    }

    @Test
    void everyAnswerPromptAsksForTheLanguageOfTheQuestionInSystemAndAtTheEnd() throws Exception {
        llm.chatHandler = m -> "Calcula facturas. InvoiceCalculator.java:" + roundLine;
        var explain = new ExplainService(llm, engine, retriever, store, new RepositoryScanner(repo), 8, 16000);

        explain.explain("src/com/acme/billing/InvoiceCalculator.java", "Â¿QuÃ© hace esta clase?");
        var spanish = llm.chatCalls.get(llm.chatCalls.size() - 1);
        assertTrue(spanish.get(0).content().contains("Write your entire answer in Spanish"));
        assertTrue(spanish.get(1).content().endsWith("Respond in Spanish."));

        explain.explain("src/com/acme/billing/InvoiceCalculator.java", "What does this class do and how is it used?");
        var english = llm.chatCalls.get(llm.chatCalls.size() - 1);
        assertTrue(english.get(0).content().contains("Write your entire answer in English"));
        assertTrue(english.get(1).content().endsWith("Respond in English."));
    }

    @Test
    void withoutAQuestionTheBrowserLanguageDecidesAndSpanishIsTheDefault() throws Exception {
        llm.chatHandler = m -> "Calcula facturas. InvoiceCalculator.java:" + roundLine;
        var explain = new ExplainService(llm, engine, retriever, store, new RepositoryScanner(repo), 8, 16000);
        String file = "src/com/acme/billing/InvoiceCalculator.java";

        explain.explain(file, null, "en-GB,en;q=0.8");
        assertTrue(llm.chatCalls.get(llm.chatCalls.size() - 1).get(1).content().endsWith("Respond in English."));
        explain.explain(file, null, null);
        assertTrue(llm.chatCalls.get(llm.chatCalls.size() - 1).get(1).content().endsWith("Respond in Spanish."));
    }

    @Test
    void diagnoseAnswersInTheLanguageOfTheProblem() {
        llm.chatHandler = msgs -> isAnswerCall(msgs) ? "Causa probable [src/com/acme/billing/InvoiceCalculator.java:"
                + roundLine + "]" : "invoice";
        var diagnose = new DiagnoseService(llm, engine, retriever, 8, 16000);

        diagnose.diagnose("the invoice total is wrong when there are many items");
        var call = llm.chatCalls.stream().filter(PipelineTest::isAnswerCall).reduce((a, b) -> b).orElseThrow();
        assertTrue(call.get(1).content().endsWith("Respond in English."));
    }

    @Test
    void citationCorrectionRoundKeepsTheLanguage() {
        int[] n = {0};
        llm.chatHandler = msgs -> {
            if (!isAnswerCall(msgs)) {
                return "invoice";
            }
            return n[0]++ == 0 ? "Mira InvoiceCalculator.java:999" : "Mira InvoiceCalculator.java:" + roundLine;
        };
        new DiagnoseService(llm, engine, retriever, 8, 16000).diagnose("Â¿Por quÃ© falla el total de la factura?");
        var last = llm.chatCalls.get(llm.chatCalls.size() - 1);
        assertTrue(last.get(last.size() - 1).content().endsWith("Respond in Spanish."));
    }

    @Test
    void anAnswerInTheWrongLanguageIsRewrittenExactlyOnce() {
        String english = "The function calculates the invoice total and this is what the code does because it is "
                + "the tax rate that is applied to the subtotal and the items are used for the rounding in the file.";
        String spanish = "La funcion calcula el total de la factura y es el impuesto que se aplica al subtotal "
                + "segun lo que hace el codigo. InvoiceCalculator.java:" + roundLine;
        llm.chatHandler = msgs -> {
            if (!isAnswerCall(msgs)) {
                return "invoice";
            }
            boolean rewriteRequested = msgs.get(msgs.size() - 1).content().contains("Rewrite it entirely in Spanish");
            return rewriteRequested ? spanish : english;
        };
        AnswerResult r = new DiagnoseService(llm, engine, retriever, 8, 16000)
                .diagnose("Â¿Por quÃ© el total de la factura sale mal?");

        assertEquals(spanish, r.answer());
        assertEquals(2, llm.chatCalls.stream().filter(PipelineTest::isAnswerCall).count());
    }

    @Test
    void ifTheRewriteIsStillInTheWrongLanguageTheOriginalAnswerIsKept() {
        String english = "The function calculates the invoice total and this is what the code does because it is "
                + "the tax rate that is applied to the subtotal and the items are used for the rounding in the file.";
        llm.chatHandler = msgs -> isAnswerCall(msgs) ? english : "invoice";
        AnswerResult r = new DiagnoseService(llm, engine, retriever, 8, 16000)
                .diagnose("Â¿Por quÃ© el total de la factura sale mal?");
        assertEquals(english, r.answer());
        assertEquals(2, llm.chatCalls.stream().filter(PipelineTest::isAnswerCall).count(),
                "respuesta + una unica reescritura; nunca se reintenta en bucle");
    }

    @Test
    void noCitationWarningIsSkippedWhenLinesCanBeInferredAndKeptOtherwise() {
        llm.chatHandler = msgs -> isAnswerCall(msgs) ? "El fallo está en la línea " + roundLine + " del redondeo."
                : "invoice";
        var diagnose = new DiagnoseService(llm, engine, retriever, 8, 16000);
        assertTrue(diagnose.diagnose("el total de la factura sale mal").warnings().isEmpty());

        llm.chatHandler = msgs -> isAnswerCall(msgs) ? "No sé, parece un problema general." : "invoice";
        assertFalse(diagnose.diagnose("el total de la factura sale mal").warnings().isEmpty());
    }

    @Test
    void largeFileReportsProgressAndStopsBetweenPartsWhenCancelled() throws Exception {
        StringBuilder sb = new StringBuilder("public class Big {\n");
        for (int i = 0; i < 400; i++) {
            sb.append("    int field").append(i).append(" = ").append(i).append("; // comentario largo de relleno\n");
        }
        Files.writeString(repo.resolve("src/Big.java"), sb.append("}\n").toString());
        runIndex();
        List<String> messages = new java.util.ArrayList<>();
        int[] calls = {0};
        llm.chatHandler = m -> {
            if (++calls[0] == 2) {
                Thread.currentThread().interrupt();
            }
            return "Resumen. Big.java:10";
        };
        var explain = new ExplainService(llm, engine, retriever, store, new RepositoryScanner(repo), 8, 4000);
        try (var ignored = com.localis.rag.Progress.bind(messages::add)) {
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> explain.explain("src/Big.java", "¿qué hace?"));
        } finally {
            Thread.interrupted();
        }
        assertEquals(2, llm.chatCalls.size(), "no se lanzan mas partes tras cancelar");
        assertTrue(messages.get(0).contains("parte 1"), messages.toString());
    }

    @Test
    void largeFilePartsAndFinalAnswerAllCarryTheLanguage() throws Exception {
        StringBuilder sb = new StringBuilder("public class Big {\n");
        for (int i = 0; i < 400; i++) {
            sb.append("    int field").append(i).append(" = ").append(i).append("; // comentario largo de relleno\n");
        }
        Files.writeString(repo.resolve("src/Big.java"), sb.append("}\n").toString());
        runIndex();
        llm.chatHandler = m -> "Resumen. Big.java:10";
        new ExplainService(llm, engine, retriever, store, new RepositoryScanner(repo), 8, 4000)
                .explain("src/Big.java", "Â¿QuÃ© hace este archivo?");

        assertTrue(llm.chatCalls.size() > 2);
        for (var call : llm.chatCalls) {
            assertTrue(call.get(0).content().contains("Write your entire answer in Spanish"));
            assertTrue(call.get(call.size() - 1).content().endsWith("Respond in Spanish.")
                    || call.get(call.size() - 1).role().equals("assistant"));
        }
    }
}
