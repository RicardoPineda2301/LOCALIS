package com.localis.docs;

import com.localis.index.CodeChunk;
import com.localis.index.IndexStore;
import com.localis.index.IndexStore.FileInfo;
import com.localis.llm.LlmClient;
import com.localis.llm.LlmClient.Message;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Genera documentación automáticamente a partir del código indexado usando el LLM.
 */
public final class AutoDocumentationService {

    private final LlmClient llm;
    private final IndexStore store;
    private final int maxContextChars;

    public AutoDocumentationService(LlmClient llm, IndexStore store, int maxContextChars) {
        this.llm = llm;
        this.store = store;
        this.maxContextChars = maxContextChars;
    }

    /**
     * Genera un resumen de la arquitectura del proyecto basado en el código indexado.
     */
    public String generateArchitectureSummary() {
        // Obtener todos los paths de archivos
        List<String> allPaths = store.filePaths().stream()
                .sorted()
                .collect(Collectors.toList());
        
        // Filtrar archivos de código fuente
        List<String> sourceFiles = allPaths.stream()
                .filter(this::isSourceFile)
                .collect(Collectors.toList());
        
        if (sourceFiles.isEmpty()) {
            return "No se encontraron archivos de código fuente en el repositorio.";
        }

        // Construir el prompt para el LLM
        StringBuilder context = new StringBuilder();
        context.append("Eres un experto en documentación de software. ");
        context.append("Analiza el siguiente código y genera una documentación técnica completa. ");
        context.append("Incluye: arquitectura general, módulos principales, tecnologías usadas, ");
        context.append("diagramas de flujo (en texto), y cualquier patrón de diseño relevante. ");
        context.append("Usa un lenguaje claro y técnico en español.\n\n");
        
        // Añadir lista de archivos
        context.append("--- Estructura del Proyecto ---\n");
        for (String path : sourceFiles) {
            Optional<FileInfo> fileInfo = store.file(path);
            fileInfo.ifPresent(fi -> {
                context.append("- ").append(path).append(" (").append(fi.lines()).append(" líneas)\n");
            });
        }
        
        // Añadir contenido de archivos clave (limitado por contexto)
        context.append("\n--- Contenido de archivos clave (resumen) ---\n");
        int charsUsed = context.length();
        int maxFilesToInclude = 3; // Limitar a solo 3 archivos para evitar saturar el contexto
        for (String path : sourceFiles.stream().limit(maxFilesToInclude).collect(Collectors.toList())) {
            List<CodeChunk> chunks = store.chunksOfFile(path);
            if (!chunks.isEmpty()) {
                StringBuilder fileContent = new StringBuilder();
                for (CodeChunk chunk : chunks) {
                    fileContent.append(chunk.text()).append("\n");
                }
                int availableChars = maxContextChars - charsUsed - 1000; // Dejar más margen
                if (availableChars > 0) {
                    String snippet = fileContent.length() <= availableChars ? fileContent.toString() : fileContent.substring(0, availableChars);
                    context.append("\n[File: ").append(path).append("]\n");
                    context.append(snippet);
                    context.append("\n...\n");
                    charsUsed = context.length();
                }
            }
        }
        
        context.append("\n\nGenera la documentación completa ahora en español:");
        
        List<Message> messages = List.of(
                Message.system("Eres un experto en documentación de software. Genera documentación técnica detallada y precisa en español."),
                Message.user(context.toString())
        );
        
        return llm.chat(messages);
    }

    private boolean isSourceFile(String path) {
        String lower = path.toLowerCase();
        // Excluir directorios comunes
        if (lower.contains("/node_modules/") || lower.contains("\\node_modules\\") ||
            lower.contains("/.git/") || lower.contains("\\.git\\") ||
            lower.contains("/target/") || lower.contains("\\target\\") ||
            lower.contains("/dist/") || lower.contains("\\dist\\") ||
            lower.contains("/build/") || lower.contains("\\build\\")) {
            return false;
        }
        // Incluir archivos de código fuente
        return lower.endsWith(".java") || lower.endsWith(".ts") || lower.endsWith(".tsx") ||
               lower.endsWith(".js") || lower.endsWith(".jsx") || lower.endsWith(".py") ||
               lower.endsWith(".cs") || lower.endsWith(".cpp") || lower.endsWith(".c") ||
               lower.endsWith(".h") || lower.endsWith(".sql") ||
               lower.endsWith(".md") || lower.endsWith(".yaml") || lower.endsWith(".yml") ||
               lower.endsWith(".json") || lower.endsWith(".xml");
    }
}
