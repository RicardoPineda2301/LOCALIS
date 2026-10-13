package com.localis.rag;

import com.localis.llm.LlmClient;
import com.localis.llm.LlmClient.Message;
import com.localis.rag.CitationVerifier.Citation;
import java.util.ArrayList;
import java.util.List;

/**
 * Pregunta al modelo, se asegura de que responda en el idioma del usuario, verifica las citas y, si alguna es
 * invalida, le pide una correccion una vez.
 */
public final class AnswerEngine {

    private final LlmClient llm;
    private final CitationVerifier verifier;

    public AnswerEngine(LlmClient llm, CitationVerifier verifier) {
        this.llm = llm;
        this.verifier = verifier;
    }

    /** Regla para el prompt de sistema. */
    public static String languageRule(String language) {
        return "Write your entire answer in " + language + ", the language of the user's question. Keep code, "
                + "identifiers, file paths and [path:line] citations exactly as they are; do not translate them.";
    }

    /** Recordatorio corto para el final del mensaje de usuario (los modelos pequenos atienden mas a lo ultimo). */
    public static String languageReminder(String language) {
        return "Respond in " + language + ".";
    }

    public static String withLanguage(String system, String language) {
        return system + "\n" + languageRule(language);
    }

    public AnswerResult ask(String system, String user, Context ctx, String language) {
        List<Message> messages = new ArrayList<>(List.of(Message.system(withLanguage(system, language)),
                Message.user(user + "\n\n" + languageReminder(language))));
        String answer = enforceLanguage(llm.chat(messages), messages, language);
        List<Citation> citations = verifier.verify(answer, ctx.shownRanges());

        List<Citation> bad = citations.stream().filter(c -> !c.verified()).toList();
        if (!bad.isEmpty()) {
            Progress.checkCancelled();
            Progress.report("Corrigiendo citas no verificadas");
            StringBuilder fb = new StringBuilder("Some of your citations are invalid:\n");
            bad.forEach(c -> fb.append("- ").append(c.raw()).append(" (").append(c.status()).append(")\n"));
            fb.append("Rewrite your answer citing ONLY file paths and line numbers that appear in the code provided.")
                    .append(" If you cannot support a claim with a visible line, say so instead of citing. ")
                    .append(languageReminder(language));
            messages.add(Message.assistant(answer));
            messages.add(Message.user(fb.toString()));
            answer = llm.chat(messages);
            citations = verifier.verify(answer, ctx.shownRanges());
        }

        List<String> warnings = new ArrayList<>();
        long unverified = citations.stream().filter(c -> !c.verified()).count();
        if (unverified > 0) {
            warnings.add(unverified + " cita(s) no pudieron verificarse contra el codigo; tratelas como no fiables.");
        }
        if (citations.isEmpty() && SnippetExtractor.inferFromText(answer, ctx.sources()).isEmpty()) {
            warnings.add("La respuesta no cita ninguna linea de codigo; verifiquela manualmente.");
        }
        return new AnswerResult(answer, citations, ctx.sources(), warnings);
    }

    /** Si la respuesta salio con claridad en otro idioma, pide una unica reescritura en el correcto. */
    private String enforceLanguage(String answer, List<Message> messages, String language) {
        String actual = Language.detectConfident(answer);
        if (actual == null || actual.equals(language)) {
            return answer;
        }
        List<Message> retry = new ArrayList<>(messages);
        Progress.checkCancelled();
        Progress.report("Ajustando el idioma de la respuesta");
        retry.add(Message.assistant(answer));
        retry.add(Message.user("Your answer is written in " + actual + ". Rewrite it entirely in " + language
                + ", keeping the same content, structure, code and [path:line] citations unchanged."));
        String rewritten = llm.chat(retry);
        String after = Language.detectConfident(rewritten);
        return after != null && !after.equals(language) ? answer : rewritten;
    }
}
