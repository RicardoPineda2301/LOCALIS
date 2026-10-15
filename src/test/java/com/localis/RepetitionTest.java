package com.localis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localis.llm.Repetition;
import org.junit.jupiter.api.Test;

class RepetitionTest {

    private static final String UNIT = "El calculo del IVA mensual se realiza en el archivo **QueriesISROpcional.sql**. ";

    @Test
    void detectsAModelStuckRepeatingTheSameSentenceAndCutsAfterOneUnit() {
        String intro = "Respuesta inicial con contenido util sobre el programa. ";
        String text = intro + UNIT.repeat(8);
        int cut = Repetition.loopCut(text);
        assertTrue(cut > 0);
        assertEquals(intro + UNIT, text.substring(0, cut));
    }

    @Test
    void detectsLoopsWithPartialLastUnitAndShorterPeriods() {
        String text = "Intro. " + "Se usa la tabla ctbdetpoliza para filtrar. ".repeat(12) + "Se usa la tab";
        assertTrue(Repetition.loopCut(text) > 0);
    }

    @Test
    void normalProseCodeAndSeparatorsAreNotLoops() {
        assertEquals(-1, Repetition.loopCut("Texto corto."));
        assertEquals(-1, Repetition.loopCut("-".repeat(600)), "una linea de guiones no es un bucle");
        assertEquals(-1, Repetition.loopCut(("=".repeat(40) + "\n").repeat(20)));
        StringBuilder normal = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            normal.append("Paso ").append(i).append(": se procesa el registro numero ").append(i * 7)
                    .append(" y se actualiza la tabla t").append(i % 5).append(" antes de continuar.\n");
        }
        assertEquals(-1, Repetition.loopCut(normal.toString()));
    }

    @Test
    void aFewLegitimateRepeatsDoNotTrigger() {
        String text = "Intro del analisis. " + UNIT.repeat(2) + "Luego se explica otra cosa distinta con detalle "
                + "suficiente para llenar espacio y demostrar que el texto avanza. " + UNIT;
        assertEquals(-1, Repetition.loopCut(text));
    }
}
