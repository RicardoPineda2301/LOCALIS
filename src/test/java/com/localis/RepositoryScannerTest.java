package com.localis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localis.config.AppConfig;
import com.localis.index.RepositoryScanner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryScannerTest {

    @TempDir
    Path tmp;

    private void file(String rel) throws Exception {
        Path f = tmp.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "x = 1\n");
    }

    private Set<String> scan(RepositoryScanner s) throws Exception {
        return s.scan().stream().map(RepositoryScanner.SourceFile::relativePath).collect(Collectors.toSet());
    }

    @Test
    void thirdPartyDependencyFoldersAreSkippedByDefault() throws Exception {
        file("src/App.java");
        for (String dep : List.of("vendor/lib/A.php", ".venv/lib/site.py", "venv/x.py", "Vendor/Upper.php",
                "node_modules/m/i.js", "app/site-packages/p.py", "bower_components/b/b.js", ".tox/t.py", "Pods/P.h")) {
            file(dep);
        }
        file("web/app.min.js");
        file("web/app.js");
        assertEquals(Set.of("src/App.java", "web/app.js"), scan(new RepositoryScanner(tmp)));
    }

    @Test
    void customIgnoredFoldersAreCaseInsensitiveAndAlsoHiddenFromTheTreeAsNonIndexable() throws Exception {
        file("src/App.java");
        file("Legacy/Old.java");
        file("generated/G.java");
        var scanner = new RepositoryScanner(tmp, List.of("legacy", " Generated "));
        assertEquals(Set.of("src/App.java"), scan(scanner));
        var names = scanner.list("").stream().map(RepositoryScanner.Entry::name).toList();
        assertTrue(names.contains("src"));
        assertFalse(names.contains("Legacy") || names.contains("generated"));
    }

    @Test
    void configParsesIgnoreDirsAsACommaSeparatedList() {
        Properties p = new Properties();
        assertEquals(List.of(), AppConfig.from(p).indexIgnoreDirs());
        p.setProperty("index.ignoreDirs", "legacy, , generated ,docs");
        assertEquals(List.of("legacy", "generated", "docs"), AppConfig.from(p).indexIgnoreDirs());
    }

    @Test
    void modernJavaScriptAndComponentFilesAreIndexed() throws Exception {
        for (String f : List.of("src/App.jsx", "src/Panel.tsx", "src/util.mjs", "src/legacy.cjs", "src/Card.vue",
                "src/Widget.svelte", "src/index.ts", "src/old.js")) {
            file(f);
        }
        assertEquals(Set.of("src/App.jsx", "src/Panel.tsx", "src/util.mjs", "src/legacy.cjs", "src/Card.vue",
                "src/Widget.svelte", "src/index.ts", "src/old.js"), scan(new RepositoryScanner(tmp)));
        assertTrue(new RepositoryScanner(tmp).list("src").stream().allMatch(RepositoryScanner.Entry::indexable));
    }

    @Test
    void omittedFilesAreCountedByReasonSoCoverageIsVisible() throws Exception {
        file("src/App.java");
        file("img/a.png");
        file("img/b.png");
        file("docs/manual.pdf");
        file(".env");
        file("Makefile");
        file("web/app.min.js");
        file("node_modules/dep/ignored.png"); // carpeta ignorada de serie: no se cuenta
        Files.createDirectories(tmp.resolve("empty"));
        Files.writeString(tmp.resolve("empty/zero.java"), ""); // vacio: ni se indexa ni se reporta
        Path big = tmp.resolve("src/Huge.sql");
        Files.write(big, new byte[(int) RepositoryScanner.MAX_FILE_BYTES + 1]);

        RepositoryScanner.Scan scan = new RepositoryScanner(tmp).scanAll();

        assertEquals(List.of("src/App.java"),
                scan.files().stream().map(RepositoryScanner.SourceFile::relativePath).toList());
        RepositoryScanner.Omitted omitted = scan.omitted();
        assertEquals(Map.of("png", 2, "pdf", 1, ".env", 1, "(sin extension)", 1, "min.js", 1), omitted.byExtension());
        assertEquals(List.of("png"), omitted.byExtension().keySet().stream().limit(1).toList(),
                "la extension mas frecuente va primero");
        assertEquals(1, omitted.tooLarge());
        assertEquals(List.of("src/Huge.sql"), omitted.tooLargeSamples());
    }

    @Test
    void scanWithoutOmissionsReportsEmptyCounts() throws Exception {
        file("src/App.java");
        RepositoryScanner.Omitted omitted = new RepositoryScanner(tmp).scanAll().omitted();
        assertEquals(RepositoryScanner.Omitted.none(), omitted);
    }

    @Test
    void gitignoreAndLocalisignoreExcludeFilesAndFoldersAndAreCounted() throws Exception {
        file("src/App.java");
        file("src/App.test.js");
        file(".env.local.js");
        file("generated/G.java");
        file("generated/H.java");
        file("Claude outputs/notas.md");
        file("docs/README.md");
        Files.writeString(tmp.resolve(".gitignore"), "generated/\n*.test.js\n.env*\ndocs/\n");
        Files.writeString(tmp.resolve(".localisignore"), "Claude outputs/\n!docs/\n");

        RepositoryScanner scanner = new RepositoryScanner(tmp);
        RepositoryScanner.Scan scan = scanner.scanAll();

        assertEquals(Set.of("src/App.java", "docs/README.md"),
                scan.files().stream().map(RepositoryScanner.SourceFile::relativePath).collect(Collectors.toSet()));
        RepositoryScanner.Omitted o = scan.omitted();
        assertEquals(2, o.ignoredFiles(), "src/App.test.js y .env.local.js");
        assertEquals(2, o.ignoredDirs(), "generated/ y Claude outputs/");
        assertTrue(o.ignoredSamples().containsAll(List.of("src/App.test.js", ".env.local.js", "generated/",
                "Claude outputs/")), o.ignoredSamples().toString());
        assertEquals(Map.of(), o.byExtension(), "lo excluido por reglas no se cuenta ademas por extension");

        var rootNames = scanner.list("").stream().map(RepositoryScanner.Entry::name).toList();
        assertFalse(rootNames.contains("generated") || rootNames.contains("Claude outputs"));
        assertTrue(rootNames.contains("docs"));
        var src = scanner.list("src");
        assertTrue(src.stream().anyMatch(e -> e.name().equals("App.java") && e.indexable()));
        assertTrue(src.stream().anyMatch(e -> e.name().equals("App.test.js") && !e.indexable()));
    }

    @Test
    void withoutIgnoreFilesEverythingIsAsBefore() throws Exception {
        file("src/App.java");
        file("src/App.test.js");
        RepositoryScanner.Scan scan = new RepositoryScanner(tmp).scanAll();
        assertEquals(2, scan.files().size());
        assertEquals(0, scan.omitted().ignoredFiles() + scan.omitted().ignoredDirs());
    }

    @Test
    void legacyDesktopSourcesAreIndexedAndTheirBinaryCompanionsAreReportedNotIndexed() throws Exception {
        List<String> text = List.of("vb6/frmFactura.frm", "vb6/modCalculos.bas", "vb6/clsCliente.cls",
                "vb6/ctlGrid.ctl", "vb6/Sistema.vbp", "delphi/UFactura.pas", "delphi/UFactura.dfm",
                "delphi/Sistema.dpr", "delphi/Comun.inc", "fox/factura.prg", "fox/menu.mpr",
                "swing/PanelFactura.form", "cobol/FACT001.cbl", "cobol/FACT001.jcl");
        for (String f : text) {
            file(f);
        }
        file("vb6/frmFactura.frx");
        file("fox/factura.scx");
        file("fox/factura.dbf");

        RepositoryScanner.Scan scan = new RepositoryScanner(tmp).scanAll();

        assertEquals(Set.copyOf(text),
                scan.files().stream().map(RepositoryScanner.SourceFile::relativePath).collect(Collectors.toSet()));
        assertEquals(Map.of("frx", 1, "scx", 1, "dbf", 1), scan.omitted().byExtension(),
                "los companeros binarios quedan fuera, pero visibles en el reporte de cobertura");
    }
}
