package com.localis.llm;

/**
 * Detecta cuando un modelo ha entrado en un bucle de repeticion ("El calculo se realiza en X. El calculo se realiza en
 * X. ...") para cortar la generacion en cuanto ocurre en vez de esperar al tope de tokens.
 */
public final class Repetition {

    /** Longitud minima del tramo periodico para considerarlo bucle; evita falsos positivos con "-----" o tablas. */
    static final int MIN_SPAN = 320;
    private static final int TAIL = 80;
    private static final int MAX_PERIOD = 1200;
    private static final int MIN_REPEATS = 4;

    private Repetition() {}

    /**
     * Si el texto termina en un bucle devuelve la posicion donde cortarlo (conserva una unidad completa); si no, -1.
     */
    public static int loopCut(String s) {
        int n = s.length();
        if (n < MIN_SPAN) {
            return -1;
        }
        String tail = s.substring(n - TAIL);
        if (letters(tail) < 20) {
            return -1;
        }
        int prev = s.lastIndexOf(tail, n - TAIL - 1);
        if (prev < 0) {
            return -1;
        }
        int period = (n - TAIL) - prev;
        if (period > MAX_PERIOD || (long) period * MIN_REPEATS > n) {
            return -1;
        }
        int start = n - period;
        while (start > 0 && s.charAt(start - 1) == s.charAt(start - 1 + period)) {
            start--;
        }
        int span = n - start;
        if (span < MIN_SPAN || span < (long) period * MIN_REPEATS) {
            return -1;
        }
        return endOfSentence(s, start + period, period);
    }

    /** La periodicidad se extiende unos caracteres hacia atras sobre el texto previo: se avanza hasta cerrar la frase. */
    private static int endOfSentence(String s, int cut, int window) {
        int end = Math.min(s.length(), cut + window);
        for (int i = cut; i < end; i++) {
            char c = s.charAt(i);
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                int e = i + 1;
                while (e < s.length() && s.charAt(e) == ' ') {
                    e++;
                }
                return e;
            }
        }
        return cut;
    }

    private static int letters(String s) {
        int c = 0;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetterOrDigit(s.charAt(i))) {
                c++;
            }
        }
        return c;
    }
}
