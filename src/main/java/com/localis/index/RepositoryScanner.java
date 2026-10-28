package com.localis.index;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Recorre el repositorio del cliente y lee los fuentes de forma segura (sin seguir enlaces simbolicos). */
public final class RepositoryScanner {

    public static final long MAX_FILE_BYTES = 1_000_000;

    /** Dependencias y artefactos de terceros: indexarlos tarda mucho y llena las busquedas de codigo ajeno. */
    private static final Set<String> IGNORED_DIRS = Set.of(".git", ".svn", ".hg", ".idea", ".vscode", ".settings",
            "target", "build", "bin", "obj", "out", "dist", "node_modules", "packages", ".gradle", "__pycache__",
            "vendor", ".venv", "venv", "site-packages", "bower_components", "jspm_packages", "pods", ".tox",
            ".mypy_cache", ".pytest_cache", ".next", ".nuxt", ".angular", "coverage", ".cache", ".terraform");

    private static final Set<String> EXTENSIONS = Set.of(
            "java", "kt", "scala", "groovy", "cs", "vb", "sql", "pks", "pkb", "prc", "fnc", "trg", "xml", "properties",
            "yml", "yaml", "gradle", "md", "js", "jsx", "mjs", "cjs", "ts", "tsx", "vue", "svelte", "py", "sh", "bat",
            "cmd", "ps1", "cbl", "cob", "cpy", "jsp", "html", "aspx", "ascx", "cshtml", "config", "cfg", "ini", "txt",
            "c", "h", "cpp", "hpp", "go", "php");

    /** Cuantos ejemplos de ruta se guardan por motivo de omision (para que el usuario vea cuales). */
    static final int SAMPLE_PATHS = 10;

    static final String NO_EXTENSION = "(sin extension)";

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    public record SourceFile(String relativePath, long size) {}

    /**
     * Lo que el escaneo dejo fuera, para que la cobertura del indice sea visible. Las carpetas ignoradas de serie
     * (node_modules, target...) no se cuentan: se omiten enteras a proposito.
     *
     * @param byExtension ficheros con una extension que no se indexa, por extension y de mayor a menor conteo
     * @param tooLarge ficheros con extension indexable pero mayores de MAX_FILE_BYTES
     * @param tooLargeSamples primeras rutas de los ficheros demasiado grandes
     * @param ignoredFiles ficheros excluidos por .gitignore / .localisignore (los que estan dentro de una carpeta
     *     excluida no se cuentan: en esa carpeta no se entra)
     * @param ignoredDirs carpetas excluidas por .gitignore / .localisignore
     * @param ignoredSamples primeras rutas excluidas (ficheros y carpetas, estas con '/' al final)
     */
    public record Omitted(Map<String, Integer> byExtension, int tooLarge, List<String> tooLargeSamples,
            int ignoredFiles, int ignoredDirs, List<String> ignoredSamples) {
        public static Omitted none() {
            return new Omitted(Map.of(), 0, List.of(), 0, 0, List.of());
        }
    }

    public record Scan(List<SourceFile> files, Omitted omitted) {}

    /** Elemento del arbol de ficheros; indexable = se incluiria en el indice. */
    public record Entry(String name, String path, boolean directory, boolean indexable, long size) {}

    private final Path root;
    private final Set<String> ignored;

    public RepositoryScanner(Path root) {
        this(root, List.of());
    }

    /** extraIgnored: nombres de carpeta adicionales a ignorar (sin distinguir mayusculas), ademas de los de serie. */
    public RepositoryScanner(Path root, java.util.Collection<String> extraIgnored) {
        this.root = root.toAbsolutePath().normalize();
        Set<String> all = new java.util.HashSet<>(IGNORED_DIRS);
        extraIgnored.forEach(n -> all.add(n.trim().toLowerCase(Locale.ROOT)));
        this.ignored = Set.copyOf(all);
    }

    private boolean isIgnoredDir(String name) {
        return ignored.contains(name.toLowerCase(Locale.ROOT));
    }

    public Path root() {
        return root;
    }

    /** Reglas de exclusion del repositorio, releidas en cada escaneo por si el cliente las cambia. */
    private IgnoreRules ignoreRules() {
        return IgnoreRules.load(root);
    }

    /**
     * Lista el contenido directo de una carpeta del repositorio (ruta relativa; vacia = raiz), carpetas primero. Las
     * carpetas excluidas (de serie o por .gitignore) no aparecen; los ficheros excluidos aparecen como no indexables.
     */
    public List<Entry> list(String relativeDir) throws IOException {
        Path dir = root.resolve(relativeDir == null ? "" : relativeDir).normalize();
        if (!dir.startsWith(root) || Files.isSymbolicLink(dir)) {
            throw new SecurityException("Ruta fuera del repositorio: " + relativeDir);
        }
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("No es una carpeta del repositorio: " + relativeDir);
        }
        IgnoreRules ignore = ignoreRules();
        List<Entry> out = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                BasicFileAttributes a;
                try {
                    a = Files.readAttributes(p, BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                } catch (IOException e) {
                    continue;
                }
                String name = p.getFileName().toString();
                if (a.isSymbolicLink() || a.isOther()) {
                    continue;
                }
                String rel = relative(p);
                if (a.isDirectory()) {
                    if (!isIgnoredDir(name) && !ignore.isIgnored(rel, true)) {
                        out.add(new Entry(name, rel, true, false, 0));
                    }
                } else {
                    out.add(new Entry(name, rel, false, a.size() > 0 && a.size() <= MAX_FILE_BYTES
                            && hasSourceExtension(p) && !ignore.isIgnored(rel, false), a.size()));
                }
            }
        }
        out.sort(Comparator.comparing((Entry e) -> !e.directory())
                .thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    public List<SourceFile> scan() throws IOException {
        return scanAll().files();
    }

    /** Ficheros a indexar mas el detalle de lo que quedo fuera y por que. */
    public Scan scanAll() throws IOException {
        List<SourceFile> out = new ArrayList<>();
        Map<String, Integer> omittedByExtension = new java.util.HashMap<>();
        int[] tooLarge = {0};
        int[] ignoredFiles = {0};
        int[] ignoredDirs = {0};
        List<String> tooLargeSamples = new ArrayList<>();
        List<String> ignoredSamples = new ArrayList<>();
        IgnoreRules ignore = ignoreRules();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                if (isIgnoredDir(dir.getFileName().toString()) || Files.isSymbolicLink(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                String rel = relative(dir);
                if (ignore.isIgnored(rel, true)) {
                    ignoredDirs[0]++;
                    if (ignoredSamples.size() < SAMPLE_PATHS) {
                        ignoredSamples.add(rel + "/");
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.size() == 0) {
                    return FileVisitResult.CONTINUE;
                }
                String rel = relative(file);
                if (isRuleFile(file)) {
                    return FileVisitResult.CONTINUE; // .gitignore / .localisignore: configuracion, no contenido
                }
                if (ignore.isIgnored(rel, false)) {
                    ignoredFiles[0]++;
                    if (ignoredSamples.size() < SAMPLE_PATHS) {
                        ignoredSamples.add(rel);
                    }
                } else if (!hasSourceExtension(file)) {
                    omittedByExtension.merge(extensionKey(file), 1, Integer::sum);
                } else if (attrs.size() > MAX_FILE_BYTES) {
                    tooLarge[0]++;
                    if (tooLargeSamples.size() < SAMPLE_PATHS) {
                        tooLargeSamples.add(relative(file));
                    }
                } else {
                    out.add(new SourceFile(relative(file), attrs.size()));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        out.sort((a, b) -> a.relativePath().compareTo(b.relativePath()));
        return new Scan(out, new Omitted(sortedByCount(omittedByExtension), tooLarge[0],
                List.copyOf(tooLargeSamples), ignoredFiles[0], ignoredDirs[0], List.copyOf(ignoredSamples)));
    }

    private static Map<String, Integer> sortedByCount(Map<String, Integer> counts) {
        Map<String, Integer> out = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private static boolean isRuleFile(Path file) {
        String name = file.getFileName().toString();
        return name.equals(IgnoreRules.GIT_IGNORE) || name.equals(IgnoreRules.LOCALIS_IGNORE);
    }

    /** Clave con la que se reporta un fichero omitido: "png", "min.js", ".env" o "(sin extension)". */
    static String extensionKey(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot == 0) {
            return name; // .env, .gitignore, .htaccess: el nombre completo es mas util que "sin extension"
        }
        if (dot < 0) {
            return NO_EXTENSION;
        }
        if (name.contains(".min.")) {
            return "min." + name.substring(dot + 1);
        }
        return name.substring(dot + 1);
    }

    /** Lee un fichero indicado por su ruta relativa; rechaza cualquier ruta que salga del repositorio. */
    public byte[] readBytes(String relativePath) throws IOException {
        Path p = root.resolve(relativePath).normalize();
        if (!p.startsWith(root) || Files.isSymbolicLink(p)) {
            throw new SecurityException("Ruta fuera del repositorio: " + relativePath);
        }
        return Files.readAllBytes(p);
    }

    /** Devuelve null si el fichero es binario. */
    public static String decode(byte[] bytes) {
        int probe = Math.min(bytes.length, 8000);
        for (int i = 0; i < probe; i++) {
            if (bytes[i] == 0) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            // Los sistemas legacy suelen estar en Windows-1252 / Latin-1.
            return new String(bytes, WINDOWS_1252);
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String relative(Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static boolean hasSourceExtension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.contains(".min.")) {
            return false; // js/css minificados: una sola linea enorme, sin valor para entender el codigo
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(name.substring(dot + 1));
    }
}
