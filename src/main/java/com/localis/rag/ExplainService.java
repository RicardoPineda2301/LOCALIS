package com.localis.rag;

import com.localis.index.CodeChunk;
import com.localis.index.IndexStore;
import com.localis.index.RepositoryScanner;
import com.localis.llm.LlmClient;
import com.localis.llm.LlmClient.Message;
import com.localis.search.Retriever;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** Explica que hace un fichero concreto o responde preguntas sobre el codigo del repositorio. */
public final class ExplainService {

    static final String SYSTEM = """
            You are a senior software engineer explaining an existing code base to a colleague who has never seen it.
            Use ONLY the code provided. Each file is introduced by a numbered header ("=== [2] FILE: ... ===") and
            every code line is prefixed with its line number ("123| code").
            Support your statements with citations written exactly like [2:45] (file number 2, line 45) or
            [2:40-52] (a range of lines). The first number is the one in the file header: write that digit, never the
            letter N. Cite ONLY files and lines that are shown. Never invent files, methods, lines or behavior.
            If the code provided is not enough to answer, say exactly what is missing.
            Be concrete and structured: purpose, main flow, inputs/outputs, dependencies, notable risks. Skip any
            section that has nothing relevant to say.
            Be concise: aim for 150-300 words and never exceed 400. Do NOT paste or retype the code; point to it with
            citations instead. Prefer short bullet points over long paragraphs.
            """;

    private static final int MAX_PARTS = 12;

    private final LlmClient llm;
    private final AnswerEngine engine;
    private final Retriever retriever;
    private final IndexStore store;
    private final RepositoryScanner scanner;
    private final int topK;
    private final int maxContextChars;

    public ExplainService(LlmClient llm, AnswerEngine engine, Retriever retriever, IndexStore store,
            RepositoryScanner scanner, int topK, int maxContextChars) {
        this.llm = llm;
        this.engine = engine;
        this.retriever = retriever;
        this.store = store;
        this.scanner = scanner;
        this.topK = topK;
        this.maxContextChars = maxContextChars;
    }

    public AnswerResult explain(String path, String question) {
        return explain(path, question, null);
    }

    /** acceptLanguage: cabecera del navegador; solo se usa si el idioma de la pregunta no se puede deducir. */
    public AnswerResult explain(String path, String question, String acceptLanguage) {
        boolean hasQuestion = question != null && !question.isBlank();
        if ((path == null || path.isBlank()) && !hasQuestion) {
            throw new IllegalArgumentException("Indique 'path' y/o 'question'");
        }
        String lang = Language.resolve(question, acceptLanguage);
        return path == null || path.isBlank() ? explainQuestion(question, lang)
                : explainFile(path, hasQuestion ? question : null, lang);
    }

    private AnswerResult explainQuestion(String question, String lang) {
        Progress.report("Buscando el codigo relacionado");
        List<CodeChunk> chunks = retriever.search(question, topK).stream().map(Retriever.Hit::chunk).toList();
        if (chunks.isEmpty()) {
            return new AnswerResult("No se encontro codigo relevante en el indice. Compruebe que el repositorio "
                    + "esta indexado.", List.of(), List.of(), List.of());
        }
        Context ctx = Context.fromChunks(chunks, maxContextChars);
        Progress.report("Consultando al modelo");
        return engine.ask(SYSTEM, "Question: " + question + "\n\nCode:\n" + ctx.text(), ctx, lang);
    }

    private AnswerResult explainFile(String rawPath, String question, String lang) {
        String path = rawPath.replace('\\', '/').replaceFirst("^\\./", "");
        if (!store.filePaths().contains(path)) {
            throw new NotFoundException("El fichero no esta en el indice: " + path);
        }
        List<String> lines;
        try {
            String text = RepositoryScanner.decode(scanner.readBytes(path));
            if (text == null) {
                throw new NotFoundException("El fichero es binario: " + path);
            }
            lines = com.localis.index.Chunker.splitLines(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String task = question == null ? "Explain what this file does." : "Question about this file: " + question;
        Context whole = Context.fromLines(path, lines, 1, lines.size());

        if (whole.text().length() <= maxContextChars) {
            Progress.report("Consultando al modelo");
            return engine.ask(SYSTEM, task + "\n\nCode:\n" + whole.text(), whole, lang);
        }
        return explainLargeFile(path, lines, task, whole, lang);
    }

    /** Fichero mas grande que la ventana de contexto: resume por partes y luego combina los resumenes. */
    private AnswerResult explainLargeFile(String path, List<String> lines, String task, Context whole, String lang) {
        int budget = Math.max(maxContextChars / 2, 2000);
        int estimated = Math.min(MAX_PARTS, Math.max(2, (int) Math.ceil(whole.text().length() / (double) budget)));
        List<String> summaries = new ArrayList<>();
        int from = 1;
        int parts = 0;
        while (from <= lines.size() && parts < MAX_PARTS) {
            Progress.checkCancelled();
            Progress.report("Analizando la parte " + (parts + 1) + " de ~" + estimated);
            int to = from;
            int chars = 0;
            while (to <= lines.size() && chars + lines.get(to - 1).length() + 8 <= budget) {
                chars += lines.get(to - 1).length() + 8;
                to++;
            }
            to = Math.max(to - 1, from);
            Context part = Context.fromLines(path, lines, from, to);
            summaries.add("Part lines " + from + "-" + to + ":\n" + llm.chat(List.of(
                    Message.system(AnswerEngine.withLanguage(SYSTEM, lang)),
                    Message.user("Summarize what this part of the file does in at most 80 words, citing line "
                            + "numbers. Do not retype code. Be brief: this summary will be merged into a larger "
                            + "explanation.\n\n"
                            + part.text() + "\n\n" + AnswerEngine.languageReminder(lang)))));
            from = to + 1;
            parts++;
        }
        Progress.checkCancelled();
        Progress.report("Combinando los resumenes en una explicacion");
        Context combined = new Context(String.join("\n\n", summaries), whole.shownRanges(), whole.sources());
        AnswerResult result = engine.ask(SYSTEM, task + "\n\nThe file was too large to read at once. Below are "
                + "summaries of consecutive parts; combine them into one coherent explanation of at most 350 words, "
                + "without repeating the same point per part.\n\n"
                + combined.text(), combined, lang);
        List<String> warnings = new ArrayList<>(result.warnings());
        warnings.add(from <= lines.size()
                ? "Fichero muy extenso: solo se analizaron las primeras " + parts + " partes."
                : "Fichero extenso: se analizo por partes (" + parts + ").");
        return new AnswerResult(result.answer(), result.citations(), result.sources(), warnings);
    }
}
