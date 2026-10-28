package com.localis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localis.index.IgnoreRules;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IgnoreRulesTest {

    @TempDir
    Path tmp;

    private static boolean file(IgnoreRules r, String path) {
        return r.isIgnored(path, false);
    }

    private static boolean dir(IgnoreRules r, String path) {
        return r.isIgnored(path, true);
    }

    @Test
    void gitignoreSyntaxTable() {
        record Case(String pattern, String path, boolean isDir, boolean ignored) {}
        List<Case> cases = List.of(
                // nombre suelto: a cualquier profundidad, fichero o carpeta
                new Case("secrets.txt", "secrets.txt", false, true),
                new Case("secrets.txt", "a/b/secrets.txt", false, true),
                new Case("secrets.txt", "a/secrets.txt/x.java", false, true),
                new Case("secrets.txt", "secrets.txt.bak", false, false),
                // comodines dentro de un componente, que no cruzan '/'
                new Case("*.log", "app.log", false, true),
                new Case("*.log", "logs/app.log", false, true),
                new Case("*.log", "logs/app.log.txt", false, false),
                new Case("?.md", "a.md", false, true),
                new Case("?.md", "ab.md", false, false),
                new Case("[Tt]emp*", "Temp1", false, true),
                new Case("[Tt]emp*", "temporal", false, true),
                new Case("[Tt]emp*", "xtemp", false, false),
                new Case("[!a-c]*.txt", "d.txt", false, true),
                new Case("[!a-c]*.txt", "b.txt", false, false),
                // solo carpetas
                new Case("build/", "build", true, true),
                new Case("build/", "build", false, false),
                new Case("build/", "src/build", true, true),
                // anclado a la raiz del fichero de reglas
                new Case("/config.json", "config.json", false, true),
                new Case("/config.json", "app/config.json", false, false),
                new Case("docs/private", "docs/private", true, true),
                new Case("docs/private", "docs/private/x.md", false, true),
                new Case("docs/private", "other/docs/private", true, false),
                new Case("doc/*.txt", "doc/a.txt", false, true),
                new Case("doc/*.txt", "doc/sub/a.txt", false, false),
                // doble asterisco
                new Case("**/logs", "logs", true, true),
                new Case("**/logs", "a/b/logs", true, true),
                new Case("**/logs/debug.log", "logs/debug.log", false, true),
                new Case("**/logs/debug.log", "a/logs/debug.log", false, true),
                new Case("abc/**", "abc/x/y.txt", false, true),
                new Case("abc/**", "xabc/y.txt", false, false),
                new Case("a/**/b", "a/b", false, true),
                new Case("a/**/b", "a/x/y/b", false, true),
                new Case("a/**/b", "a/x/bb", false, false),
                // escapes y comentarios
                new Case("\\#notacomment", "#notacomment", false, true),
                new Case("# comentario", "# comentario", false, false),
                new Case("\\!important", "!important", false, true),
                new Case("con\\ espacio", "con espacio", false, true),
                new Case("sinespacio   ", "sinespacio", false, true),
                new Case("", "x", false, false));
        for (Case c : cases) {
            IgnoreRules r = IgnoreRules.of(c.pattern(), "");
            assertEquals(c.ignored(), r.isIgnored(c.path(), c.isDir()),
                    "patron '" + c.pattern() + "' sobre '" + c.path() + "'" + (c.isDir() ? " (carpeta)" : ""));
        }
    }

    @Test
    void laterRulesWinAndNegationReincludes() {
        IgnoreRules r = IgnoreRules.of("*.log\n!keep.log\n", "");
        assertTrue(file(r, "a.log"));
        assertFalse(file(r, "keep.log"));
        assertFalse(file(r, "logs/keep.log"));

        IgnoreRules reversed = IgnoreRules.of("!keep.log\n*.log\n", "");
        assertTrue(file(reversed, "keep.log"), "la ultima regla que coincide manda");
    }

    @Test
    void localisignoreHasPriorityOverGitignore() {
        IgnoreRules r = IgnoreRules.of("docs/\n", "!docs/\nClaude outputs/\n");
        assertFalse(dir(r, "docs"), ".localisignore puede volver a incluir lo que .gitignore excluye");
        assertTrue(dir(r, "Claude outputs"));
        assertTrue(file(r, "Claude outputs/notas.md"), "lo que cuelga de una carpeta excluida esta excluido");
    }

    @Test
    void crlfEndingsAndWindowsEditorsAreFine() {
        IgnoreRules r = IgnoreRules.of("*.tmp\r\nbin/\r\n", "");
        assertTrue(file(r, "x.tmp"));
        assertTrue(dir(r, "bin"));
    }

    @Test
    void nestedGitignoreAppliesOnlyBelowItsFolderAndOverridesTheRoot() throws Exception {
        Files.writeString(tmp.resolve(".gitignore"), "*.sql\n");
        Files.createDirectories(tmp.resolve("supabase/migrations"));
        Files.writeString(tmp.resolve("supabase/.gitignore"), "!*.sql\n.temp/\n");
        Files.createDirectories(tmp.resolve("db"));

        IgnoreRules r = IgnoreRules.load(tmp);
        assertTrue(file(r, "db/old.sql"), "la raiz excluye .sql");
        assertFalse(file(r, "supabase/migrations/001.sql"), "el .gitignore de supabase/ los vuelve a incluir");
        assertTrue(dir(r, "supabase/.temp"));
        assertFalse(dir(r, ".temp"), "las reglas de supabase/ no aplican fuera de supabase/");
    }

    @Test
    void gitInfoExcludeIsReadToo() throws Exception {
        Files.createDirectories(tmp.resolve(".git/info"));
        Files.writeString(tmp.resolve(".git/info/exclude"), "scratch/\n");
        assertTrue(dir(IgnoreRules.load(tmp), "scratch"));
    }

    @Test
    void aSymlinkedOrHugeRuleFileIsIgnoredForSafety() throws Exception {
        Files.writeString(tmp.resolve("real-rules"), "*.java\n");
        try {
            Files.createSymbolicLink(tmp.resolve(".gitignore"), tmp.resolve("real-rules"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // sin enlaces simbolicos en este sistema: no hay nada que comprobar
        }
        assertFalse(file(IgnoreRules.load(tmp), "A.java"), "un .gitignore que es enlace simbolico no se lee");

        Files.delete(tmp.resolve(".gitignore"));
        Files.writeString(tmp.resolve(".gitignore"), "*.java\n" + "#".repeat((int) IgnoreRules.MAX_RULE_FILE_BYTES));
        assertFalse(file(IgnoreRules.load(tmp), "A.java"), "un fichero de reglas enorme no se lee");
    }

    @Test
    void aHostilePatternDoesNotHangTheMatcher() {
        String pattern = "*a".repeat(40) + "b";
        String text = "a".repeat(2000);
        long start = System.nanoTime();
        assertFalse(file(IgnoreRules.of(pattern, ""), text));
        assertTrue(System.nanoTime() - start < 2_000_000_000L, "debe resolverse en bien menos de 2 s");
    }
}
