package com.localis.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Herramienta del PROVEEDOR (no de uso del cliente).
 *   keygen <dir>                       genera private.pem y public.pem
 *   issue --key private.pem --customer X [--expires yyyy-MM-dd] [--machines N] [--machine-id id]...
 *         [--modules a,b] --out license.json
 *   fingerprint                        muestra el identificador de esta maquina
 * La clave privada NUNCA debe distribuirse ni guardarse en el repositorio.
 */
public final class LicenseTool {

    private LicenseTool() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Uso: keygen <dir> | issue --key ... | fingerprint");
            return;
        }
        switch (args[0]) {
            case "fingerprint" -> System.out.println(MachineFingerprint.current());
            case "keygen" -> keygen(Path.of(args.length > 1 ? args[1] : "."));
            case "issue" -> issue(Arrays.copyOfRange(args, 1, args.length));
            default -> System.out.println("Comando desconocido: " + args[0]);
        }
    }

    private static void keygen(Path dir) throws Exception {
        Files.createDirectories(dir);
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.writeString(dir.resolve("private.pem"), pem("PRIVATE KEY", kp.getPrivate().getEncoded()));
        Files.writeString(dir.resolve("public.pem"), pem("PUBLIC KEY", kp.getPublic().getEncoded()));
        System.out.println("Generadas private.pem (SECRETA) y public.pem en " + dir.toAbsolutePath());
    }

    private static void issue(String[] a) throws Exception {
        Map<String, List<String>> opts = new java.util.HashMap<>();
        for (int i = 0; i + 1 < a.length; i += 2) {
            opts.computeIfAbsent(a[i].replaceFirst("^--", ""), k -> new java.util.ArrayList<>()).add(a[i + 1]);
        }
        PrivateKey key = readPrivateKey(Files.readString(Path.of(required(opts, "key"))));
        String expires = opts.containsKey("expires") ? opts.get("expires").get(0) : null;
        if (expires != null) {
            LocalDate.parse(expires);
        }
        List<String> machineIds = opts.getOrDefault("machine-id", List.of());
        List<String> modules = opts.containsKey("modules") ? List.of(opts.get("modules").get(0).split(",")) : List.of();
        int machines = opts.containsKey("machines") ? Integer.parseInt(opts.get("machines").get(0))
                : Math.max(1, machineIds.size());

        License unsigned = new License(required(opts, "customer"), LocalDate.now().toString(), expires, machines,
                machineIds, modules, null);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(key);
        s.update(unsigned.signedPayload().getBytes(StandardCharsets.UTF_8));
        License signed = new License(unsigned.customer(), unsigned.issuedAt(), unsigned.expiresAt(),
                unsigned.maxMachines(), unsigned.machineIds(), unsigned.modules(),
                Base64.getEncoder().encodeToString(s.sign()));

        Path out = Path.of(opts.containsKey("out") ? opts.get("out").get(0) : "license.json");
        Files.writeString(out, new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(signed));
        System.out.println("Licencia escrita en " + out.toAbsolutePath());
    }

    private static String required(Map<String, List<String>> opts, String name) {
        if (!opts.containsKey(name)) {
            throw new IllegalArgumentException("Falta --" + name);
        }
        return opts.get(name).get(0);
    }

    private static PrivateKey readPrivateKey(String pem) throws Exception {
        String b64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
