package com.localis.index;

/** Fragmento de codigo indexado. Las lineas son 1-based e inclusivas. */
public record CodeChunk(
        long id,
        String path,
        int startLine,
        int endLine,
        String kind,
        String symbol,
        String text,
        float[] embedding) {

    public CodeChunk withEmbedding(float[] e) {
        return new CodeChunk(id, path, startLine, endLine, kind, symbol, text, e);
    }

    /** Texto enviado al modelo de embeddings: incluye ruta y simbolo para dar contexto. */
    public String embeddingInput() {
        String t = text.length() > 4000 ? text.substring(0, 4000) : text;
        return path + (symbol == null ? "" : " :: " + symbol) + "\n" + t;
    }
}
