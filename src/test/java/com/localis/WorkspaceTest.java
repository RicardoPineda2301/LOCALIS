package com.localis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localis.config.AppConfig;
import com.localis.workspace.Workspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTest {

    @TempDir
    Path tmp;

    private AppConfig cfg(String repoPath) {
        Properties p = new Properties();
        p.setProperty("data.dir", tmp.resolve("data").toString().replace('\\', '/'));
        if (repoPath != null) {
            p.setProperty("repo.path", repoPath.replace('\\', '/'));
        }
        return AppConfig.from(p);
    }

    @Test
    void startsEmptyAndRefusesAiWorkUntilARepoIsChosen() throws Exception {
        try (var ws = new Workspace(cfg(null), new TestSupport.FakeLlm())) {
            assertTrue(ws.repo().isEmpty());
            assertEquals(0, ws.stats().files());
            assertThrows(IllegalArgumentException.class, ws::explain);
            assertThrows(IllegalArgumentException.class, ws::diagnose);
        }
    }

    @Test
    void rejectsFilesAndMissingFolders() throws Exception {
        Path file = Files.writeString(tmp.resolve("a.txt"), "x");
        try (var ws = new Workspace(cfg(null), new TestSupport.FakeLlm())) {
            assertThrows(IllegalArgumentException.class, () -> ws.open(file));
            assertThrows(IllegalArgumentException.class, () -> ws.open(tmp.resolve("no-existe")));
            assertTrue(ws.repo().isEmpty());
        }
    }

    @Test
    void remembersTheLastChosenRepoAcrossRestartsOverridingConfig() throws Exception {
        Path a = Files.createDirectories(tmp.resolve("a"));
        Path b = Files.createDirectories(tmp.resolve("b"));
        try (var ws = new Workspace(cfg(a.toString()), new TestSupport.FakeLlm())) {
            assertEquals(a.toAbsolutePath().normalize(), ws.repo().orElseThrow());
            ws.open(b);
        }
        try (var ws = new Workspace(cfg(a.toString()), new TestSupport.FakeLlm())) {
            assertEquals(b.toAbsolutePath().normalize(), ws.repo().orElseThrow());
        }
    }

    @Test
    void fallsBackToConfiguredRepoWhenTheRememberedOneDisappeared() throws Exception {
        Path a = Files.createDirectories(tmp.resolve("a"));
        Path b = Files.createDirectories(tmp.resolve("b"));
        try (var ws = new Workspace(cfg(null), new TestSupport.FakeLlm())) {
            ws.open(b);
        }
        Files.delete(b);
        try (var ws = new Workspace(cfg(a.toString()), new TestSupport.FakeLlm())) {
            assertEquals(a.toAbsolutePath().normalize(), ws.repo().orElseThrow());
        }
    }
}
