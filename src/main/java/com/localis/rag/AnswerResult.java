package com.localis.rag;

import com.localis.rag.CitationVerifier.Citation;
import java.util.List;

public record AnswerResult(String answer, List<Citation> citations, List<Context.SourceRef> sources,
        List<String> warnings) {

    public boolean allCitationsVerified() {
        return citations.stream().allMatch(Citation::verified);
    }
}
