package com.localis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localis.rag.CitationVerifier;
import com.localis.rag.CitationVerifier.Status;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CitationVerifierTest {

    private final CitationVerifier verifier = new CitationVerifier(
            () -> Set.of("src/com/acme/billing/InvoiceCalculator.java", "src/a/Util.java", "src/b/Util.java"),
            p -> 100);

    private final Map<String, List<int[]>> shown = Map.of(
            "src/com/acme/billing/InvoiceCalculator.java", List.of(new int[] {10, 30}, new int[] {31, 50}));

    private Status statusOf(String text) {
        return verifier.verify(text, shown).get(0).status();
    }

    @Test
    void verifiesRealCitationsInContext() {
        assertEquals(Status.VERIFIED, statusOf("Error en [src/com/acme/billing/InvoiceCalculator.java:15]"));
        assertEquals(Status.VERIFIED, statusOf("ver InvoiceCalculator.java:12-48"));
        assertEquals(Status.VERIFIED, statusOf("ver InvoiceCalculator.java:L20"));
    }

    @Test
    void rejectsFabricatedCitations() {
        assertEquals(Status.FILE_NOT_FOUND, statusOf("ver Ghost.java:10"));
        assertEquals(Status.OUT_OF_RANGE, statusOf("ver InvoiceCalculator.java:313"));
        assertEquals(Status.OUT_OF_RANGE, statusOf("ver InvoiceCalculator.java:40-20"));
        assertEquals(Status.NOT_IN_CONTEXT, statusOf("ver InvoiceCalculator.java:5"));
        assertEquals(Status.NOT_IN_CONTEXT, statusOf("ver InvoiceCalculator.java:45-60"));
        assertEquals(Status.AMBIGUOUS_FILE, statusOf("ver Util.java:5"));
    }

    @Test
    void aBareFileNameIsResolvedToTheOnlyCandidateTheModelActuallySaw() {
        var seesB = Map.of("src/b/Util.java", List.of(new int[] {1, 40}));
        var c = verifier.verify("ver Util.java:5-8", seesB).get(0);
        assertEquals(Status.VERIFIED, c.status());
        assertEquals("src/b/Util.java", c.path());

        var seesBoth = Map.of("src/a/Util.java", List.of(new int[] {1, 40}), "src/b/Util.java",
                List.of(new int[] {1, 40}));
        assertEquals(Status.AMBIGUOUS_FILE, verifier.verify("ver Util.java:5", seesBoth).get(0).status());
        assertEquals(Status.AMBIGUOUS_FILE, verifier.verify("ver Util.java:5", Map.of()).get(0).status());
    }

    @Test
    void windowsPathsAreNormalizedAndDuplicatesCollapsed() {
        var cites = verifier.verify("a src\\com\\acme\\billing\\InvoiceCalculator.java:15 y de nuevo "
                + "src\\com\\acme\\billing\\InvoiceCalculator.java:15", shown);
        assertEquals(1, cites.size());
        assertTrue(cites.get(0).verified());
    }

    @Test
    void plainTextWithoutCitationsYieldsNothing() {
        assertTrue(verifier.verify("Todo parece bien a las 10:30.", shown).isEmpty());
    }

    @Test
    void numberedCitationsAreResolvedToTheNumberedFileAndInvalidNumbersAreLeft() {
        List<String> files = List.of("src/com/acme/billing/InvoiceCalculator.java", "db/tax.sql");
        assertEquals("Ver [src/com/acme/billing/InvoiceCalculator.java:15] y [db/tax.sql:2-3], no [3:9] ni [0:1].",
                CitationVerifier.resolveNumbered("Ver [1:15] y [2: 2-3], no [3:9] ni [0:1].", files));
        assertEquals("[1:L15]", CitationVerifier.resolveNumbered("[1:L15]", List.of()), "sin ficheros no se toca");
        assertEquals("[src/com/acme/billing/InvoiceCalculator.java:15]",
                CitationVerifier.resolveNumbered("[1:L15]", files));
    }

    @Test
    void aNumberedCitationThatSurvivesResolutionIsReportedAsInvalid() {
        List<CitationVerifier.Citation> out = verifier.verify(
                "Ver [src/com/acme/billing/InvoiceCalculator.java:15] y [7:12]", shown);
        assertEquals(2, out.size());
        assertEquals(Status.VERIFIED, out.get(0).status());
        assertEquals(Status.FILE_NOT_FOUND, out.get(1).status());
        assertEquals("[7:12]", out.get(1).raw());
    }

    @Test
    void aCitationWithTheLetterNCopiedFromThePromptIsReportedAsInvalid() {
        List<CitationVerifier.Citation> out = verifier.verify(
                "Ver [N:355], [n:12-20], [N:LINE] y [N:START-END]; nada mas.", shown);
        assertEquals(4, out.size());
        assertTrue(out.stream().allMatch(c -> c.status() == Status.FILE_NOT_FOUND));
        assertEquals("[N:355]", out.get(0).raw());
        assertEquals(355, out.get(0).startLine());
        assertEquals(12, out.get(1).startLine());
        assertEquals(20, out.get(1).endLine());
        assertEquals(0, out.get(2).startLine(), "sin numero de linea no se inventa uno");
    }

    @Test
    void theLetterNDoesNotSwallowRealCitationsInTheSameAnswer() {
        List<String> files = List.of("src/com/acme/billing/InvoiceCalculator.java");
        String resolved = CitationVerifier.resolveNumbered("Ver [1:15] y [N:355].", files);
        assertEquals("Ver [src/com/acme/billing/InvoiceCalculator.java:15] y [N:355].", resolved,
                "la cita numerica se resuelve y la de la N se deja tal cual");
        List<CitationVerifier.Citation> out = verifier.verify(resolved, shown);
        assertEquals(2, out.size());
        assertEquals(Status.VERIFIED, out.get(0).status());
        assertEquals(Status.FILE_NOT_FOUND, out.get(1).status());
        assertEquals("[N:355]", out.get(1).raw());
    }

    @Test
    void thePlaceholderPrefixFromOldPromptsIsStrippedAndMatchedByFileName() {
        assertEquals(Status.VERIFIED, statusOf("ver [path/InvoiceCalculator.java:15]"));
        assertEquals(Status.VERIFIED, statusOf("ver [ruta/InvoiceCalculator.java:15]"));
        assertEquals(Status.FILE_NOT_FOUND, statusOf("ver [path/Ghost.java:15]"));
        assertEquals("src/com/acme/billing/InvoiceCalculator.java",
                verifier.verify("ver [path/InvoiceCalculator.java:15]", shown).get(0).path());
    }
}
