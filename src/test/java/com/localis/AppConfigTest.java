package com.localis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.localis.config.AppConfig;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class AppConfigTest {

    @Test
    void contextBudgetFollowsTheModelWindowWhenNotConfigured() {
        AppConfig cfg = AppConfig.defaults();
        assertEquals(AppConfig.defaultMaxContextChars(8192, 1500), cfg.maxContextChars());
        assertEquals(17_076, cfg.maxContextChars(), "8192 de ventana menos respuesta y prompt, a 3 caracteres por token");

        Properties bigger = new Properties();
        bigger.setProperty("ollama.numCtx", "16384");
        assertEquals(AppConfig.defaultMaxContextChars(16384, 1500), AppConfig.from(bigger).maxContextChars());
        assertEquals(41_652, AppConfig.from(bigger).maxContextChars());
    }

    @Test
    void anExplicitContextBudgetWins() {
        Properties p = new Properties();
        p.setProperty("retrieval.maxContextChars", "16000");
        assertEquals(16000, AppConfig.from(p).maxContextChars());
    }

    @Test
    void aTinyWindowStillLeavesAUsableMinimum() {
        assertEquals(4000, AppConfig.defaultMaxContextChars(2048, 1500));
    }
}
