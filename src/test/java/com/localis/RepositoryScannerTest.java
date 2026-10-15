package com.localis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localis.config.AppConfig;
import com.localis.index.RepositoryScanner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}
