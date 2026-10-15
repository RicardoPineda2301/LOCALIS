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
}
