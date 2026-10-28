package com.localis.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localis.index.CodeChunk;
import com.localis.index.IndexStore;
import com.localis.index.IndexStore.FileInfo;
import com.localis.llm.LlmClient;
import com.localis.llm.LlmClient.Message;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Genera documentación automáticamente a partir del código indexado usando el LLM.
 */
public final class AutoDocumentationService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int BATCH = 12;

    private final LlmClient llm;
    private final IndexStore store;
    private final int maxContextChars;
    private java.util.function.BiConsumer<String, Double> progress = (text, fraction) -> {};

    /** Recibe un texto de estado y la fraccion (0..1) completada de la fase en curso. */
    public void onProgress(java.util.function.BiConsumer<String, Double> listener) {
        this.progress = listener;
    }

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
        
        // Lista de archivos (acotada para no saturar el contexto)
        context.append("--- Estructura del Proyecto ---\n");
        for (String path : sourceFiles.stream().limit(120).collect(Collectors.toList())) {
            Optional<FileInfo> fileInfo = store.file(path);
            fileInfo.ifPresent(fi -> {
                context.append("- ").append(path).append(" (").append(fi.lines()).append(" líneas)\n");
            });
        }

        // Archivos clave primero: manifiestos, esquemas, configuracion y puntos de entrada
        context.append("\n--- Contenido de archivos clave ---\n");
        List<String> key = sourceFiles.stream()
                .filter(p -> keyRank(p) < 9)
                .sorted(java.util.Comparator.comparingInt(this::keyRank).thenComparing(p -> p))
                .limit(10)
                .collect(Collectors.toList());
        int budget = maxContextChars - 2000;
        for (String path : key) {
            StringBuilder fileContent = new StringBuilder();
            for (CodeChunk chunk : store.chunksOfFile(path)) {
                fileContent.append(chunk.text()).append("\n");
            }
            int available = Math.min(2500, budget - context.length());
            if (available <= 200 || fileContent.length() == 0) {
                continue;
            }
            context.append("\n[File: ").append(path).append("]\n");
            context.append(fileContent.length() <= available ? fileContent.toString() : fileContent.substring(0, available));
            context.append("\n...\n");
        }
        
        context.append("\n\nGenera la documentación completa ahora en español:");
        
        List<Message> messages = List.of(
                Message.system("Eres un experto en documentación de software. Genera documentación técnica detallada y precisa en español."),
                Message.user(context.toString())
        );
        
        return llm.chat(messages);
    }

    /**
     * Rellena los marcadores de una plantilla con informacion del proyecto. Fecha y version se resuelven
     * directamente; el resto lo redacta el LLM por lotes a partir del resumen del proyecto.
     */
    public Map<Integer, String> fillSlots(List<DocxTemplateEngine.Slot> slots, String summary, String date,
            String version) {
        Map<Integer, String> out = new HashMap<>();
        List<DocxTemplateEngine.Slot> pending = new ArrayList<>();
        for (var s : slots) {
            String t = s.text();
            if (t.equals("[DD/MM/YYYY]")) {
                out.put(s.index(), date);
            } else if (t.equals("[X.X]") && version != null && !version.isBlank()) {
                out.put(s.index(), version);
            } else {
                pending.add(s);
            }
        }
        String ctx = summary.length() > maxContextChars / 2 ? summary.substring(0, maxContextChars / 2) : summary;
        int totalBatches = (pending.size() + BATCH - 1) / BATCH;
        for (int i = 0; i < pending.size(); i += BATCH) {
            progress.accept("Redactando contenido (" + (i / BATCH + 1) + "/" + totalBatches + ")",
                    (double) (i / BATCH) / totalBatches);
            List<DocxTemplateEngine.Slot> batch = pending.subList(i, Math.min(i + BATCH, pending.size()));
            ObjectNode items = JSON.createObjectNode();
            for (var s : batch) {
                ObjectNode n = items.putObject(String.valueOf(s.index()));
                n.put("contexto", s.hint());
                n.put("instruccion", s.text());
            }
            String prompt = "Descripcion del proyecto:\n" + ctx + "\n\n"
                    + "Estoy rellenando una plantilla de documentacion. Cada elemento tiene un 'contexto' (titulo o etiqueta "
                    + "que lo precede) y una 'instruccion' entre corchetes que indica que escribir. Redacta SIEMPRE EN "
                    + "ESPANOL el texto final de cada uno, breve (una o dos frases, o un valor), deduciendolo del proyecto "
                    + "con criterio razonable (tecnologias, modulos, patrones). Para autores, revisores, responsables, "
                    + "equipos o aprobadores escribe \"LOCALIS\". Si el apartado no aplica a este proyecto (p. ej. cache, "
                    + "mensajeria o infraestructura que no se usan) o no se puede deducir, responde exactamente \"SKIP\": "
                    + "ese apartado se eliminara del documento. Nunca dejes la instruccion original ni texto vacio. "
                    + "Responde SOLO con un objeto JSON {\"id\": \"texto\"} con los mismos ids.\n\n" + items;
            for (int attempt = 0; attempt < 2 && batch.stream().anyMatch(s -> !out.containsKey(s.index())); attempt++) {
                try {
                    String raw = llm.chat(List.of(
                            Message.system("Redactas documentacion tecnica en espanol. Respondes unicamente con JSON valido."),
                            Message.user(prompt)));
                    int a = raw.indexOf('{');
                    int b = raw.lastIndexOf('}');
                    if (a >= 0 && b > a) {
                        JsonNode node = JSON.readTree(raw.substring(a, b + 1));
                        for (var s : batch) {
                            JsonNode v = node.get(String.valueOf(s.index()));
                            if (v != null && v.isTextual() && !v.asText().isBlank()) {
                                out.put(s.index(), v.asText().trim());
                            }
                        }
                    }
                } catch (Exception e) {
                    // Se reintenta una vez; si vuelve a fallar el lote queda como N/D.
                }
            }
        }
        // Si el LLM fallo en un lote no se borran apartados: se deja un texto visible para completarlo a mano.
        for (var s : slots) {
            out.putIfAbsent(s.index(), "Pendiente de completar");
        }
        return out;
    }

    /** Prioridad de un archivo como fuente de contexto: menor es mas relevante, 9 = no clave. */
    /** Pide al LLM la estructura de cada diagrama (nodos y relaciones) y la dibuja; los que fallen se omiten. */
    public Map<Integer, byte[]> generateDiagrams(List<DocxTemplateEngine.Diagram> diagrams, String summary) {
        Map<Integer, byte[]> out = new HashMap<>();
        String ctx = summary.length() > maxContextChars / 2 ? summary.substring(0, maxContextChars / 2) : summary;
        int n = 0;
        for (var d : diagrams) {
            progress.accept("Dibujando diagramas (" + (++n) + "/" + diagrams.size() + ")",
                    (double) (n - 1) / diagrams.size());
            String prompt = "Descripcion del proyecto:\n" + ctx + "\n\n"
                    + "Crea la estructura del diagrama \"" + d.title() + "\" para el apartado \"" + d.hint() + "\". "
                    + "Usa entre 4 y 10 nodos reales del proyecto (modulos, servicios, base de datos, clientes, sistemas "
                    + "externos) y las relaciones entre ellos. Etiquetas en espanol, de 25 caracteres como maximo. "
                    + "Responde SOLO con JSON: {\"nodes\":[{\"id\":\"a\",\"label\":\"...\"}],"
                    + "\"edges\":[{\"from\":\"a\",\"to\":\"b\",\"label\":\"...\"}]}";
            for (int attempt = 0; attempt < 2 && !out.containsKey(d.index()); attempt++) {
                try {
                    String raw = llm.chat(List.of(
                            Message.system("Disenas diagramas de arquitectura. Respondes unicamente con JSON valido."),
                            Message.user(prompt)));
                    int a = raw.indexOf('{');
                    int b = raw.lastIndexOf('}');
                    if (a >= 0 && b > a) {
                        byte[] png = DiagramRenderer.render(JSON.readTree(raw.substring(a, b + 1)));
                        if (png != null) {
                            out.put(d.index(), png);
                        }
                    }
                } catch (Exception e) {
                    // Se reintenta una vez; si falla, la caja de la plantilla se elimina.
                }
            }
        }
        return out;
    }

    private int keyRank(String path) {
        String name = path.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase();
        if (name.equals("package.json") || name.equals("pom.xml") || name.startsWith("build.gradle")
                || name.equals("requirements.txt") || name.equals("pyproject.toml") || name.endsWith(".csproj")) {
            return 0;
        }
        if (name.endsWith(".prisma") || name.startsWith("docker-compose") || name.equals("dockerfile")) {
            return 1;
        }
        if (name.equals("readme.md")) {
            return 2;
        }
        if (name.equals("main.ts") || name.equals("app.module.ts") || name.equals("application.properties")
                || name.equals("application.yml") || name.equals("app.py") || name.equals("main.py")
                || name.equals("program.cs")) {
            return 3;
        }
        if (name.endsWith(".module.ts") || name.endsWith(".controller.ts") || name.endsWith("application.java")) {
            return 4;
        }
        return 9;
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
               lower.endsWith(".h") || lower.endsWith(".sql") || lower.endsWith(".prisma") || lower.endsWith(".toml") ||
               lower.endsWith("dockerfile") ||
               lower.endsWith(".md") || lower.endsWith(".yaml") || lower.endsWith(".yml") ||
               lower.endsWith(".json") || lower.endsWith(".xml");
    }
}
