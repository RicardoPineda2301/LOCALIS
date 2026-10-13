package com.localis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.localis.rag.Language;
import org.junit.jupiter.api.Test;

class LanguageTest {

    @Test
    void detectsTheLanguageOfTypicalQuestions() {
        assertEquals("Spanish", Language.detect("¿Cómo se bloquean los hosts no permitidos?", 1));
        assertEquals("Spanish", Language.detect("que hace este programa", 1));
        assertEquals("English", Language.detect("What does this file do and how is the total calculated?", 1));
        assertEquals("Portuguese", Language.detect("Como é calculado o total da fatura? não entendi", 1));
        assertEquals("French", Language.detect("Comment est calculé le total de la facture dans ce fichier ?", 1));
        assertEquals("German", Language.detect("Wie wird die Summe berechnet und was macht diese Datei?", 1));
    }

    @Test
    void returnsNullWhenThereIsNoEvidence() {
        assertNull(Language.detect("factura total", 1));
        assertNull(Language.detect("NullPointerException ctbDocsReversadosS", 1));
        assertNull(Language.detect("  ", 1));
        assertNull(Language.detect(null, 1));
    }

    @Test
    void fallsBackToTheBrowserLanguageThenToSpanish() {
        assertEquals("English", Language.resolve("invoice total", "en-US,en;q=0.9"));
        assertEquals("Spanish", Language.resolve("invoice total", "es-ES,es;q=0.9"));
        assertEquals("Spanish", Language.resolve("invoice total", null));
        assertEquals("Spanish", Language.resolve("invoice total", "ja-JP"));
        assertEquals("Portuguese", Language.fromAcceptLanguage("pt-BR,pt;q=0.9"));
    }

    @Test
    void theQuestionWinsOverTheBrowserLanguage() {
        assertEquals("Spanish", Language.resolve("¿Qué hace esta consulta?", "en-US"));
        assertEquals("English", Language.resolve("What does this query do?", "es-ES"));
    }

    @Test
    void answerDetectionIgnoresCodeAndCitationsAndIsConservative() {
        String english = "The function calculates the invoice total and this is what the code does because it is "
                + "the tax rate that is applied to the subtotal and the items are used for the rounding in the file.";
        assertEquals("English", Language.detectConfident(english));
        String spanishWithEnglishCode = "La funcion calcula el total de la factura y es el impuesto que se aplica "
                + "al subtotal. ```java\nif (the is are was were of to in and or for with what how) { }\n``` "
                + "Ver [src/The.java:10] para los detalles de esto que hace el codigo.";
        assertEquals("Spanish", Language.detectConfident(spanishWithEnglishCode));
        assertNull(Language.detectConfident("Calcula facturas. InvoiceCalculator.java:12"));
    }
}
