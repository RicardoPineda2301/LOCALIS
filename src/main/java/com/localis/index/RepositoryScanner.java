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
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Recorre el repositorio del cliente y lee los fuentes de forma segura (sin seguir enlaces simbolicos). */
public final class RepositoryScanner {

    static final long MAX_FILE_BYTES = 1_000_000;

    private static final Set<String> IGNORED_DIRS = Set.of(".git", ".svn", ".hg", ".idea", ".vscode", ".settings",
            "target", "build", "bin", "obj", "out", "dist", "node_modules", "packages", ".gradle", "__pycache__");

    private static final Set<String> EXTENSIONS = Set.of(
            "java", "kt", "scala", "groovy", "cs", "vb", "sql", "pks", "pkb", "prc", "fnc", "trg", "xml", "properties",
            "yml", "yaml", "gradle", "md", "js", "ts", "py", "sh", "bat", "cmd", "ps1", "cbl", "cob", "cpy", "jsp",
            "html", "aspx", "ascx", "cshtml", "config", "cfg", "ini", "txt", "c", "h", "cpp", "hpp", "go", "php");

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    public record SourceFile(String relativePath, long size) {}

    private final Path root;

    public RepositoryScanner(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public List<SourceFile> scan() throws IOException {
        List<SourceFile> out = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && (IGNORED_DIRS.contains(dir.getFileName().toString())
                        || Files.isSymbolicLink(dir))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && !attrs.isSymbolicLink() && attrs.size() > 0
                        && attrs.size() <= MAX_FILE_BYTES && hasSourceExtension(file)) {
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
        return out;
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
        int dot = name.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(name.substring(dot + 1));
    }
}
