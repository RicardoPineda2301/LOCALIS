package com.localis;

import com.localis.llm.LlmClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Utilidades de test: LLM simulado y repositorio de ejemplo. */
public final class TestSupport {

    private TestSupport() {}

    /** Embeddings deterministas por bolsa de palabras; el chat lo decide el test. */
    public static final class FakeLlm implements LlmClient {
        public final List<List<Message>> chatCalls = new ArrayList<>();
        public Function<List<Message>, String> chatHandler = m -> "ok";
        public boolean embeddingsFail;

        @Override
        public String chat(List<Message> messages) {
            chatCalls.add(List.copyOf(messages));
            return chatHandler.apply(messages);
        }

        @Override
        public List<float[]> embed(List<String> inputs) {
            if (embeddingsFail) {
                throw new com.localis.llm.LlmException("embeddings caidos");
            }
            return inputs.stream().map(FakeLlm::vector).toList();
        }

        static float[] vector(String text) {
            float[] v = new float[128];
            for (String t : com.localis.search.Tokenizer.tokens(text)) {
                v[Math.floorMod(t.hashCode(), 128)] += 1;
            }
            return v;
        }

        @Override
        public Status status() {
            return new Status(true, true, true, "fake");
        }
    }

    public static final String INVOICE = """
            package com.acme.billing;

            import java.math.BigDecimal;

            /** Calculo de facturas. */
            public class InvoiceCalculator {

                private static final double TAX_RATE = 0.21;

                /** Calcula el total de la factura con impuestos. */
                public double calculateInvoiceTotal(double subtotal, int items) {
                    double tax = subtotal * TAX_RATE;
                    double total = subtotal + tax;
                    // el redondeo se hace sobre el numero de items y no sobre el total
                    return Math.round(total / items) * items;
                }

                public String nextInvoiceNumber(int last) {
                    return "INV-" + (last + 1);
                }
            }
            """;

    public static final String CUSTOMER = """
            package com.acme.crm;

            public class CustomerService {
                public String greet(String name) {
                    return "Hola " + name;
                }
            }
            """;

    public static Path sampleRepo(Path root) throws IOException {
        Path billing = Files.createDirectories(root.resolve("src/com/acme/billing"));
        Files.writeString(billing.resolve("InvoiceCalculator.java"), INVOICE);
        Path crm = Files.createDirectories(root.resolve("src/com/acme/crm"));
        Files.writeString(crm.resolve("CustomerService.java"), CUSTOMER);
        Files.writeString(Files.createDirectories(root.resolve("db")).resolve("tax.sql"),
                "CREATE TABLE tax_rate (id INT, rate DECIMAL(5,2));\nSELECT rate FROM tax_rate;\n");
        // Carpeta que debe ignorarse
        Files.writeString(Files.createDirectories(root.resolve("target")).resolve("Ignored.java"), "class Ignored {}");
        return root;
    }
}
