package com.localis;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localis.rag.AnswerEngine;
import com.localis.rag.CitationVerifier.Citation;
import com.localis.rag.CitationVerifier.Status;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnswerEngineTest {

    @Test
    void theCorrectionMessageShowsAConcreteExampleInsteadOfThePatternWithTheLetterN() {
        String msg = AnswerEngine.correctionMessage(
                List.of(new Citation("[N:355]", "fuente [N]", 355, 355, Status.FILE_NOT_FOUND)), "Spanish");
        assertTrue(msg.contains("[N:355] (FILE_NOT_FOUND)"), "lista la cita invalida");
        assertTrue(msg.contains("[2:45]"), "ejemplo concreto");
        assertTrue(msg.contains("[2:40-52]"), "ejemplo concreto de rango");
        assertFalse(msg.contains("N:LINE"), "sin el patron que el modelo copia");
        assertFalse(msg.contains("N:START"), "sin el patron que el modelo copia");
        assertTrue(msg.endsWith("Respond in Spanish."));
    }
}
