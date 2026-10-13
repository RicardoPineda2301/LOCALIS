package com.localis.docs;

import com.localis.rag.NotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Lista las plantillas disponibles y genera documentos con la marca del cliente. */
public final class DocumentationService {

    public record Request(String template, String projectName, String version, String author, String team) {}

    public record Document(String fileName, byte[] content) {}

    private final Path templatesDir;
    private final DocxTemplateEngine engine;
    private final String company;
    private final Path logoPath;

    public DocumentationService(Path templatesDir, String company, Path logoPath) throws IOException {
        this.templatesDir = templatesDir.toAbsolutePath().normalize();
        this.engine = new DocxTemplateEngine(this.templatesDir.resolve("assets").resolve("logo_placeholder.png"));
        this.company = company;
        this.logoPath = logoPath;
    }

    /** Identificadores de plantilla (nombre sin extension). */
    public List<String> templates() throws IOException {
        if (!Files.isDirectory(templatesDir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(templatesDir)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".docx"))
                    .map(n -> n.substring(0, n.length() - 5))
                    .sorted().toList();
        }
    }

    public Document generate(Request req) throws IOException {
        // El id debe coincidir con una plantilla existente: impide rutas arbitrarias.
        String id = templates().stream().filter(t -> t.equals(req.template())).findFirst()
                .orElseThrow(() -> new NotFoundException("Plantilla desconocida: " + req.template()));
        byte[] logo = logoPath != null && Files.isRegularFile(logoPath) ? Files.readAllBytes(logoPath) : null;
        String monthYear = LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH));
        var fields = new DocxTemplateEngine.Fields(company, req.projectName(), req.version(), monthYear,
                req.author(), req.team());
        byte[] content = engine.generate(templatesDir.resolve(id + ".docx"), fields, logo);
        String project = req.projectName() == null ? "" : req.projectName().replaceAll("[^\\p{L}\\p{N} _.-]", "").trim();
        return new Document(project.isEmpty() ? id + ".docx" : id + " - " + project + ".docx", content);
    }
}
