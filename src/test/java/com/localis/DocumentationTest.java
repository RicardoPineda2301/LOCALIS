package com.localis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localis.docs.DocumentationService;
import com.localis.docs.DocxTemplateEngine;
import com.localis.docs.LogoFitter;
import com.localis.rag.NotFoundException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentationTest {

    static final Path TEMPLATES = Path.of("templates");

    @TempDir
    Path tmp;

    private static Map<String, byte[]> unzip(byte[] docx) throws Exception {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(docx))) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                out.put(e.getName(), z.readAllBytes());
            }
        }
        return out;
    }

    private static String visibleText(Map<String, byte[]> parts, String name) {
        String xml = new String(parts.get(name), StandardCharsets.UTF_8);
        return xml.replaceAll("<[^>]+>", "");
    }

    private Path logo(int w, int h, int rgb) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                img.setRGB(x, y, rgb);
            }
        }
        Path p = tmp.resolve("client-logo.png");
        ImageIO.write(img, "png", p.toFile());
        return p;
    }

    @Test
    void generatesBrandedDocumentWithClientDataAndLogo() throws Exception {
        Path logo = logo(400, 100, 0xFF0000);
        var svc = new DocumentationService(TEMPLATES, "ACME S.A.", logo);

        var doc = svc.generate(new DocumentationService.Request("4. Code_Documentation_Template", "Facturacion",
                "2.1", "Ana", "Backend"));

        assertEquals("4. Code_Documentation_Template - Facturacion.docx", doc.fileName());
        Map<String, byte[]> parts = unzip(doc.content());
        String body = visibleText(parts, "word/document.xml");
        assertTrue(body.contains("Facturacion") && body.contains("Version 2.1"), body.substring(0, 300));
        assertTrue(body.contains("Prepared by: Ana  |  Backend"));
        assertFalse(body.contains("[Project Name]") || body.contains("[Author Name]") || body.contains("[Team / Area]"));
        assertTrue(visibleText(parts, "word/footer1.xml").contains("ACME S.A."));
        assertFalse(parts.keySet().stream().filter(n -> n.startsWith("word/footer"))
                .anyMatch(n -> visibleText(parts, n).contains("{{COMPANY_NAME}}")));

        // El logo se sustituyo, conserva las dimensiones del lienzo original y el aspecto del logo del cliente
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(parts.get("word/media/image1.png")));
        assertEquals(2640, img.getWidth());
        assertEquals(1485, img.getHeight());
        assertEquals(0xFF0000, img.getRGB(img.getWidth() / 2, img.getHeight() / 2) & 0xFFFFFF);
        assertEquals(0, img.getRGB(2, 2) >>> 24, "fuera del logo debe ser transparente");
    }

    @Test
    void onlyTheCoverVersionIsReplacedNotHistoryTables() throws Exception {
        var svc = new DocumentationService(TEMPLATES, "ACME", null);
        var doc = svc.generate(new DocumentationService.Request("10. Version_Control_Changes_Template", "P", "3.0",
                null, null));
        String body = visibleText(unzip(doc.content()), "word/document.xml");
        assertEquals(1, Pattern.compile("Version 3\\.0  \\|").matcher(body).results().count());
        assertTrue(body.contains("v[X.X]"), "las versiones de las tablas de historial no se tocan");
    }

    @Test
    void withoutLogoOrCompanyTemplateKeepsPlaceholderAndNoVisibleToken() throws Exception {
        var svc = new DocumentationService(TEMPLATES, "", null);
        Map<String, byte[]> parts = unzip(svc.generate(new DocumentationService.Request(
                "1. Architecture_Documentation_Template", "X", "1.0", "A", "T")).content());
        assertArrayEquals(Files.readAllBytes(TEMPLATES.resolve("assets/logo_placeholder.png")),
                parts.get("word/media/image1.png"));
        assertFalse(visibleText(parts, "word/footer1.xml").contains("{{"));
    }

    @Test
    void specialCharactersInFieldsAreEscaped() throws Exception {
        var svc = new DocumentationService(TEMPLATES, "Smith & <Sons>", null);
        var doc = svc.generate(new DocumentationService.Request("2. Design_Documentation_Template",
                "A&B", "1", "x", "y"));
        Map<String, byte[]> parts = unzip(doc.content());
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(parts.get("word/document.xml")));
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(parts.get("word/footer1.xml")));
        assertTrue(visibleText(parts, "word/footer1.xml").contains("Smith &amp; &lt;Sons&gt;"));
    }

    @Test
    void unknownTemplatesAndPathTraversalAreRejected() throws Exception {
        var svc = new DocumentationService(TEMPLATES, "", null);
        for (String bad : new String[] {"nope", "../pom", "..\\pom", "assets/logo_placeholder", null}) {
            assertThrows(NotFoundException.class, () -> svc.generate(
                    new DocumentationService.Request(bad, "p", "1", "a", "t")), String.valueOf(bad));
        }
        assertEquals(12, svc.templates().size());
    }

    @Test
    void logoFitterKeepsAspectRatioAndRejectsNonImages() throws Exception {
        byte[] wide = Files.readAllBytes(logo(1000, 100, 0x00FF00));
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(LogoFitter.fitToCanvas(wide, 800, 400)));
        assertEquals(800, out.getWidth());
        assertEquals(400, out.getHeight());
        assertEquals(0x00FF00, out.getRGB(400, 200) & 0xFFFFFF);
        assertEquals(0x00FF00, out.getRGB(400, 200 + 30) & 0xFFFFFF, "ancho 640 -> alto 64: +-32 px");
        assertEquals(0, out.getRGB(400, 200 + 60) >>> 24);
        assertThrows(IllegalArgumentException.class, () -> LogoFitter.fitToCanvas(
                "no soy una imagen".getBytes(StandardCharsets.UTF_8), 100, 100));
    }

    @Test
    void engineWithoutPlaceholderFingerprintReplacesAnyPng() throws Exception {
        byte[] out = new DocxTemplateEngine(null).generate(TEMPLATES.resolve("5. API_Documentation_Template.docx"),
                new DocxTemplateEngine.Fields("C", "P", "1", "M", "A", "T"), Files.readAllBytes(logo(50, 50, 0x0000FF)));
        assertTrue(out.length > 1000);
    }
}
