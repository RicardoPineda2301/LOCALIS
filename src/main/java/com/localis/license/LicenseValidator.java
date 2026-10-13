package com.localis.license;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Optional;

/** Valida licencias sin conexion: firma Ed25519, caducidad, maquina y modulos. */
public final class LicenseValidator {

    public record Result(boolean valid, String reason, License license) {
        public boolean moduleEnabled(String module) {
            return valid && (license.modules().isEmpty() || license.modules().contains(module));
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PublicKey publicKey;
    private final String machineId;
    private final java.time.Clock clock;

    public LicenseValidator(PublicKey publicKey, String machineId, java.time.Clock clock) {
        this.publicKey = publicKey;
        this.machineId = machineId;
        this.clock = clock;
    }

    public Result validate(Path licenseFile) {
        if (!Files.isRegularFile(licenseFile)) {
            return invalid("No se encontro el fichero de licencia: " + licenseFile);
        }
        License lic;
        try {
            lic = JSON.readValue(Files.readString(licenseFile, StandardCharsets.UTF_8), License.class);
        } catch (IOException e) {
            return invalid("Fichero de licencia ilegible");
        }
        return validate(lic);
    }

    public Result validate(License lic) {
        if (!signatureOk(lic)) {
            return new Result(false, "Firma de licencia invalida", lic);
        }
        if (lic.expiresAt() != null) {
            try {
                if (LocalDate.now(clock).isAfter(LocalDate.parse(lic.expiresAt()))) {
                    return new Result(false, "Licencia caducada el " + lic.expiresAt(), lic);
                }
            } catch (DateTimeParseException e) {
                return new Result(false, "Fecha de caducidad invalida", lic);
            }
        }
        if (!lic.machineIds().isEmpty() && !lic.machineIds().contains(machineId)) {
            return new Result(false, "La licencia no esta emitida para esta maquina (" + machineId + ")", lic);
        }
        return new Result(true, "ok", lic);
    }

    private boolean signatureOk(License lic) {
        if (lic.signature() == null) {
            return false;
        }
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(publicKey);
            s.update(lic.signedPayload().getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getDecoder().decode(lic.signature()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private static Result invalid(String reason) {
        return new Result(false, reason, new License(null, null, null, 0, null, null, null));
    }

    /** Lee una clave publica en PEM (X.509 / SubjectPublicKeyInfo). */
    public static PublicKey readPublicKey(String pem) throws GeneralSecurityException {
        String b64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
    }

    /** Carga la clave publica del fichero configurado o, si no hay, la incluida en el jar. */
    public static Optional<PublicKey> loadPublicKey(Path configured) throws IOException, GeneralSecurityException {
        if (configured != null) {
            return Optional.of(readPublicKey(Files.readString(configured)));
        }
        try (var in = LicenseValidator.class.getResourceAsStream("/license-public.pem")) {
            return in == null ? Optional.empty()
                    : Optional.of(readPublicKey(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }
}
