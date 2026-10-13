package com.localis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localis.license.License;
import com.localis.license.LicenseValidator;
import com.localis.license.LicenseTool;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LicenseTest {

    static final Clock TODAY = Clock.fixed(Instant.parse("2026-10-13T10:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tmp;

    private final KeyPair vendor = keys();
    private final LicenseValidator validator = new LicenseValidator(vendor.getPublic(), "machine-A", TODAY);

    private static KeyPair keys() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private License sign(String expires, List<String> machines, List<String> modules) throws Exception {
        License u = new License("ACME", "2026-01-01", expires, 1, machines, modules, null);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(vendor.getPrivate());
        s.update(u.signedPayload().getBytes(StandardCharsets.UTF_8));
        return new License(u.customer(), u.issuedAt(), u.expiresAt(), u.maxMachines(), u.machineIds(), u.modules(),
                Base64.getEncoder().encodeToString(s.sign()));
    }

    @Test
    void validAnnualAndPerpetualLicenses() throws Exception {
        assertTrue(validator.validate(sign("2027-10-13", List.of(), List.of())).valid());
        assertTrue(validator.validate(sign("2026-10-13", List.of(), List.of())).valid(), "ultimo dia incluido");
        assertTrue(validator.validate(sign(null, List.of(), List.of())).valid());
    }

    @Test
    void expiredLicenseIsRejected() throws Exception {
        var r = validator.validate(sign("2026-10-12", List.of(), List.of()));
        assertFalse(r.valid());
        assertTrue(r.reason().contains("caducada"));
    }

    @Test
    void tamperedLicenseIsRejected() throws Exception {
        License ok = sign("2026-12-31", List.of(), List.of("docs"));
        License extended = new License(ok.customer(), ok.issuedAt(), "2099-12-31", ok.maxMachines(), ok.machineIds(),
                ok.modules(), ok.signature());
        License moreModules = new License(ok.customer(), ok.issuedAt(), ok.expiresAt(), ok.maxMachines(),
                ok.machineIds(), List.of("docs", "diagnose"), ok.signature());
        assertFalse(validator.validate(extended).valid());
        assertFalse(validator.validate(moreModules).valid());
        assertFalse(validator.validate(new License("ACME", "2026-01-01", null, 1, null, null, null)).valid());
    }

    @Test
    void licenseSignedByAnotherVendorKeyIsRejected() throws Exception {
        var other = new LicenseValidator(keys().getPublic(), "machine-A", TODAY);
        assertFalse(other.validate(sign(null, List.of(), List.of())).valid());
    }

    @Test
    void machineBindingAndModules() throws Exception {
        License bound = sign(null, List.of("machine-A", "machine-B"), List.of("docs"));
        var ok = validator.validate(bound);
        assertTrue(ok.valid());
        assertTrue(ok.moduleEnabled("docs"));
        assertFalse(ok.moduleEnabled("diagnose"));

        var elsewhere = new LicenseValidator(vendor.getPublic(), "machine-Z", TODAY).validate(bound);
        assertFalse(elsewhere.valid());
        assertFalse(elsewhere.moduleEnabled("docs"));

        assertTrue(validator.validate(sign(null, List.of(), List.of())).moduleEnabled("anything"));
    }

    @Test
    void missingOrCorruptFileIsInvalid() throws Exception {
        assertFalse(validator.validate(tmp.resolve("none.json")).valid());
        Path bad = Files.writeString(tmp.resolve("bad.json"), "{no es json");
        assertFalse(validator.validate(bad).valid());
    }

    @Test
    void vendorToolRoundTrip() throws Exception {
        Path keyDir = tmp.resolve("keys");
        LicenseTool.main(new String[] {"keygen", keyDir.toString()});
        Path out = tmp.resolve("license.json");
        String expires = LocalDate.now().plusYears(1).toString();
        LicenseTool.main(new String[] {"issue", "--key", keyDir.resolve("private.pem").toString(), "--customer", "ACME",
            "--expires", expires, "--machine-id", "machine-A", "--modules", "explain,docs", "--out", out.toString()});

        var pub = LicenseValidator.readPublicKey(Files.readString(keyDir.resolve("public.pem")));
        var res = new LicenseValidator(pub, "machine-A", Clock.systemDefaultZone()).validate(out);
        assertTrue(res.valid(), res.reason());
        assertEquals("ACME", res.license().customer());
        assertTrue(res.moduleEnabled("explain"));
        assertFalse(res.moduleEnabled("diagnose"));
        assertEquals(1, res.license().maxMachines());
    }
}
