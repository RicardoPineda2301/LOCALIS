package com.localis.docs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/**
 * Personaliza una plantilla .docx: sustituye el logo y los campos de la plantilla, sin tocar su formato.
 * El logo de la plantilla se localiza por huella SHA-256 del logo de ejemplo (templates/assets).
 */
public final class DocxTemplateEngine {

    public static final String COMPANY_TOKEN = "{{COMPANY_NAME}}";
    public static final String VERSION_TOKEN = "[X.X]";

    private static final Pattern TEXT_NODE = Pattern.compile("(<w:t(?:\\s[^>]*)?>)([^<]*)(</w:t>)");
    private static final Pattern WORD_PART = Pattern.compile("word/(document|header\\d*|footer\\d*)\\.xml");

    /** Campos de la plantilla -> valor. El orden no importa. */
    public record Fields(String company, String projectName, String version, String monthYear, String author,
            String team) {}

    private final byte[] placeholderHash;

    /** @param placeholderLogo logo de ejemplo que contienen las plantillas (puede ser null). */
    public DocxTemplateEngine(Path placeholderLogo) throws IOException {
        this.placeholderHash = placeholderLogo != null && Files.isRegularFile(placeholderLogo)
                ? sha256(Files.readAllBytes(placeholderLogo)) : null;
    }

    public byte[] generate(Path template, Fields fields, byte[] logo) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipFile zip = new ZipFile(template.toFile()); ZipOutputStream out = new ZipOutputStream(bos)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                byte[] data = zip.getInputStream(e).readAllBytes();
                String name = e.getName();
                if (logo != null && isTemplateLogo(name, data)) {
                    var img = ImageIO.read(new ByteArrayInputStream(data));
                    data = LogoFitter.fitToCanvas(logo, img.getWidth(), img.getHeight());
                } else if (WORD_PART.matcher(name).matches()) {
                    data = replaceFields(new String(data, StandardCharsets.UTF_8), fields,
                            name.equals("word/document.xml")).getBytes(StandardCharsets.UTF_8);
                }
                ZipEntry copy = new ZipEntry(name);
                copy.setTime(e.getTime());
                out.putNextEntry(copy);
                out.write(data);
                out.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private boolean isTemplateLogo(String name, byte[] data) throws IOException {
        if (!name.startsWith("word/media/") || data.length == 0 || !name.toLowerCase().endsWith(".png")) {
            return false;
        }
        return placeholderHash == null || MessageDigest.isEqual(placeholderHash, sha256(data));
    }

    static String replaceFields(String xml, Fields f, boolean isBody) {
        boolean[] versionDone = {false};
        Matcher m = TEXT_NODE.matcher(xml);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String text = m.group(2);
            // Sin nombre de empresa configurado, el marcador no debe quedar visible en el pie.
            text = text.replace(COMPANY_TOKEN, notBlank(f.company()) ? escape(f.company()) : "");
            text = swap(text, "[Project Name]", f.projectName());
            text = swap(text, "[Month Year]", f.monthYear());
            text = swap(text, "[Author Name]", f.author());
            text = swap(text, "[Team / Area]", f.team());
            // [X.X] tambien aparece en las tablas de historial; solo se cambia el de la portada.
            if (isBody && !versionDone[0] && text.contains(VERSION_TOKEN) && notBlank(f.version())) {
                text = text.replace(VERSION_TOKEN, escape(f.version()));
                versionDone[0] = true;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + text + m.group(3)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String swap(String text, String token, String value) {
        return notBlank(value) && text.contains(token) ? text.replace(token, escape(value)) : text;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static byte[] sha256(byte[] b) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
