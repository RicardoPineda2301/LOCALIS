package com.localis.rag;

import com.localis.llm.LlmClient;
import com.localis.llm.LlmClient.Message;
import com.localis.rag.CitationVerifier.Citation;
import java.util.ArrayList;
import java.util.List;

/** Pregunta al modelo, verifica las citas y, si alguna es invalida, le pide una correccion una vez. */
public final class AnswerEngine {

    static final String LANGUAGE_RULE = "Answer in the same language as the user's question.";

    private final LlmClient llm;
    private final CitationVerifier verifier;

    public AnswerEngine(LlmClient llm, CitationVerifier verifier) {
        this.llm = llm;
        this.verifier = verifier;
    }

    public AnswerResult ask(String system, String user, Context ctx) {
        List<Message> messages = new ArrayList<>(List.of(Message.system(system), Message.user(user)));
        String answer = llm.chat(messages);
        List<Citation> citations = verifier.verify(answer, ctx.shownRanges());

        List<Citation> bad = citations.stream().filter(c -> !c.verified()).toList();
        if (!bad.isEmpty()) {
            StringBuilder fb = new StringBuilder("Some of your citations are invalid:\n");
            bad.forEach(c -> fb.append("- ").append(c.raw()).append(" (").append(c.status()).append(")\n"));
            fb.append("Rewrite your answer citing ONLY file paths and line numbers that appear in the code provided.")
                    .append(" If you cannot support a claim with a visible line, say so instead of citing.");
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
        if (citations.isEmpty()) {
            warnings.add("La respuesta no cita ninguna linea de codigo; verifiquela manualmente.");
        }
        return new AnswerResult(answer, citations, ctx.sources(), warnings);
    }
}
