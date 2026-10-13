package com.localis.llm;

import java.util.List;

/** Abstraccion del motor de IA local. Ollama es la implementacion por defecto. */
public interface LlmClient {

    record Message(String role, String content) {
        public static Message system(String c) {
            return new Message("system", c);
        }

        public static Message user(String c) {
            return new Message("user", c);
        }

        public static Message assistant(String c) {
            return new Message("assistant", c);
        }
    }

    record Status(boolean reachable, boolean chatModelInstalled, boolean embedModelInstalled, String detail) {}

    String chat(List<Message> messages);

    /** Un vector por cada texto de entrada, en el mismo orden. */
    List<float[]> embed(List<String> inputs);

    Status status();
}
