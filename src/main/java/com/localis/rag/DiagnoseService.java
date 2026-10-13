package com.localis.rag;

import com.localis.index.CodeChunk;
import com.localis.llm.LlmClient;
import com.localis.llm.LlmClient.Message;
import com.localis.llm.LlmException;
import com.localis.search.Retriever;
import java.util.List;

/** Dada la descripcion de un fallo, localiza el codigo sospechoso y propone hipotesis con evidencia verificada. */
public final class DiagnoseService {

    static final String SYSTEM = """
            You are a senior engineer doing a read-only root-cause analysis on a legacy code base, offline.
            You receive a problem description and code excerpts. Every code line is prefixed with its line number
            ("123| code"). Rules:
            - Use ONLY the code provided. Never invent files, lines, methods or data.
            - Cite every piece of evidence as [path/File.ext:LINE] or [path/File.ext:START-END], only with lines shown.
            - Do NOT rewrite or fix the code. Identify where the fault most likely is and why. You may state in one
              sentence what the faulty line should do instead.
            - Give the single most likely hypothesis first. Add a second or third ONLY if each is backed by different
              code evidence. Never pad the list, repeat the same line, or blame a signature or comment without a
              concrete reason. For each: location, reasoning, and confidence (high / medium / low).
            - If the excerpts are insufficient, say what additional code, data or logs you would need.
            """;

    private static final String KEYWORD_SYSTEM = """
            Output ONLY a comma-separated list of up to 12 identifiers and keywords, in English and in the form they
            would appear in source code (camelCase or snake_case), that are likely related to the problem below.
            No explanations.""";

    private final LlmClient llm;
    private final AnswerEngine engine;
    private final Retriever retriever;
    private final int topK;
    private final int maxContextChars;

    public DiagnoseService(LlmClient llm, AnswerEngine engine, Retriever retriever, int topK, int maxContextChars) {
        this.llm = llm;
        this.engine = engine;
        this.retriever = retriever;
        this.topK = topK;
        this.maxContextChars = maxContextChars;
    }

    public AnswerResult diagnose(String problem) {
        return diagnose(problem, null);
    }

    /** acceptLanguage: cabecera del navegador; solo se usa si el idioma del problema no se puede deducir. */
    public AnswerResult diagnose(String problem, String acceptLanguage) {
        if (problem == null || problem.isBlank()) {
            throw new IllegalArgumentException("Indique 'problem'");
        }
        Progress.report("Buscando el codigo relacionado");
        List<CodeChunk> chunks = retriever.search(problem + " " + expandKeywords(problem), topK).stream()
                .map(Retriever.Hit::chunk).toList();
        if (chunks.isEmpty()) {
            return new AnswerResult("No se encontro codigo relacionado en el indice. Compruebe que el repositorio "
                    + "esta indexado o reformule el problema con nombres de pantallas, tablas o funciones.",
                    List.of(), List.of(), List.of());
        }
        Context ctx = Context.fromChunks(chunks, maxContextChars);
        Progress.report("Analizando el codigo con el modelo");
        return engine.ask(SYSTEM, "Problem: " + problem + "\n\nCode:\n" + ctx.text(), ctx,
                Language.resolve(problem, acceptLanguage));
    }

    // Puentea el idioma del usuario ("facturacion") con los identificadores del codigo ("invoice").
    private String expandKeywords(String problem) {
        try {
            Progress.report("Interpretando el problema");
            String out = llm.chat(List.of(Message.system(KEYWORD_SYSTEM), Message.user(problem)));
            return out.length() > 500 ? out.substring(0, 500) : out;
        } catch (LlmException e) {
            Progress.checkCancelled();
            return "";
        }
    }
}
