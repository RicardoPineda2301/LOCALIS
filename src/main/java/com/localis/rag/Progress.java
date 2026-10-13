package com.localis.rag;

import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * Canal de progreso y cancelacion del trabajo en curso en este hilo. Los servicios informan de en que paso van y
 * comprueban si se ha cancelado entre llamadas largas al modelo (la cancelacion es una interrupcion del hilo).
 */
public final class Progress {

    private static final ThreadLocal<Consumer<String>> SINK = new ThreadLocal<>();

    private Progress() {}

    /** Asocia el receptor de mensajes al hilo actual; cerrar el resultado lo desasocia. */
    public static AutoCloseable bind(Consumer<String> sink) {
        SINK.set(sink);
        return SINK::remove;
    }

    public static void report(String message) {
        Consumer<String> s = SINK.get();
        if (s != null) {
            s.accept(message);
        }
    }

    /** Lanza CancellationException si el hilo fue interrumpido; no limpia la marca de interrupcion. */
    public static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Cancelado");
        }
    }
}
