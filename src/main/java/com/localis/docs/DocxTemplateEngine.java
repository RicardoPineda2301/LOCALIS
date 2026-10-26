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

    /**
     * Parrafo de la plantilla que aun contiene un marcador [..] por rellenar.
     * @param index posicion del parrafo en document.xml
     * @param text  texto actual (el marcador con sus instrucciones)
     * @param hint  texto fijo que lo precede (titulo o etiqueta), para dar contexto
     */
    public record Slot(int index, String text, String hint) {}

    /** Caja "[ Insert ... Diagram here ]" de la plantilla, donde va un diagrama. */
    public record Diagram(int index, String title, String hint) {}

    private static final Pattern DIAGRAM_BOX = Pattern.compile("\\[\\s*Insert([^\\]]*?)\\bhere\\s*\\]");

    /** Lista las cajas de diagrama de cualquier plantilla. */
    public static java.util.List<Diagram> extractDiagrams(byte[] docx) throws IOException {
        var out = new java.util.ArrayList<Diagram>();
        String last = "";
        int i = 0;
        Matcher m = PARAGRAPH.matcher(documentXml(docx));
        while (m.find()) {
            String text = paragraphText(m.group()).trim();
            Matcher box = DIAGRAM_BOX.matcher(text);
            if (box.matches()) {
                out.add(new Diagram(i, box.group(1).trim(), last));
            } else if (!text.isEmpty() && !PLACEHOLDER.matcher(text).find()) {
                last = text;
            }
            i++;
        }
        return out;
    }

    // Los parrafos vacios vienen como <w:p .../>: si no se distinguen, la regex se traga el parrafo siguiente.
    private static final String PARA_RX = "<w:p(?:\\s[^>]*?)?/>|<w:p(?:\\s[^>]*[^/>])?>.*?</w:p>";
    private static final Pattern PARAGRAPH = Pattern.compile(PARA_RX, Pattern.DOTALL);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[[^\\]]+\\]");

    /** Lista los parrafos con marcadores pendientes de cualquier plantilla (excepto los de insertar diagramas). */
    public static java.util.List<Slot> extractSlots(byte[] docx) throws IOException {
        var slots = new java.util.ArrayList<Slot>();
        String last = "";
        String prev = "";
        int i = 0;
        Matcher m = PARAGRAPH.matcher(documentXml(docx));
        while (m.find()) {
            String text = paragraphText(m.group());
            if (!text.isBlank()) {
                if (PLACEHOLDER.matcher(text).find() && !DIAGRAM_BOX.matcher(text.trim()).matches()) {
                    // Se pide al LLM solo el texto: el numero del titulo o paso lo conserva la plantilla
                    Matcher num = NUM_PREFIX.matcher(text.trim());
                    String slotText = num.matches() ? num.group(2) : text.trim();
                    slots.add(new Slot(i, slotText, (prev + " > " + last).trim()));
                } else {
                    prev = last;
                    last = text.trim();
                }
            }
            i++;
        }
        return slots;
    }

    private static final Pattern BLOCK = Pattern.compile("<w:tbl>.*?</w:tbl>|" + PARA_RX, Pattern.DOTALL);
    private static final Pattern ROW = Pattern.compile("<w:tr[ >].*?</w:tr>", Pattern.DOTALL);
    private static final Pattern NUM_PREFIX = Pattern.compile("^(\\d+(?:\\.\\d+)*\\.?)\\s+(.+)$", Pattern.DOTALL);
    private static final Pattern HEADING_STYLE = Pattern.compile("<w:pStyle w:val=\"Heading\\d\"");

    /** 1 o 2 si el parrafo es un titulo numerado ("3." o "3.1") con estilo de titulo; 0 en otro caso. */
    private static int headingLevel(String xml, String text) {
        Matcher n = NUM_PREFIX.matcher(text);
        if (!n.matches() || !HEADING_STYLE.matcher(xml).find()) {
            return 0;
        }
        long dots = n.group(1).chars().filter(c -> c == '.').count() - (n.group(1).endsWith(".") ? 1 : 0);
        return dots == 0 ? 1 : dots == 1 ? 2 : 0;
    }

    /** Elemento del cuerpo (parrafo, tabla o texto intermedio) con sus contadores de marcadores. */
    private static final class Block {
        String xml;
        boolean gap;
        boolean table;
        boolean skipped;
        boolean removed;
        byte[] image;
        int level;
        int orig;
        int live;
    }

    /** Valores que el LLM usa para decir "este apartado no aplica". */
    static boolean isSkip(String value) {
        String v = value.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("^[\\s\"'.]+|[\\s\"'.]+$", "");
        return v.isEmpty() || v.equals("skip") || v.equals("n/d") || v.equals("n/a") || v.equals("no aplica");
    }

    /**
     * Sustituye los marcadores (indice -> texto). Los que se marcan como no aplicables eliminan su fila, su
     * parrafo o el apartado entero si no queda contenido, y la numeracion de los titulos se recalcula.
     */
    public static byte[] applySlots(byte[] docx, java.util.Map<Integer, String> values) throws IOException {
        return applySlots(docx, values, java.util.Map.of());
    }

    /** Igual que {@link #applySlots(byte[], java.util.Map)}; ademas sustituye cada caja de diagrama por su PNG (o la quita). */
    public static byte[] applySlots(byte[] docx, java.util.Map<Integer, String> values,
            java.util.Map<Integer, byte[]> diagrams) throws IOException {
        String xml = documentXml(docx);
        var blocks = new java.util.ArrayList<Block>();
        Matcher m = BLOCK.matcher(xml);
        int pos = 0;
        int pIdx = 0;
        while (m.find()) {
            if (m.start() > pos) {
                blocks.add(gap(xml.substring(pos, m.start())));
            }
            Block b = new Block();
            b.xml = m.group();
            b.table = b.xml.startsWith("<w:tbl>");
            pIdx = b.table ? processTable(b, values, pIdx) : processParagraph(b, values, diagrams, pIdx);
            blocks.add(b);
            pos = m.end();
        }
        blocks.add(gap(xml.substring(pos)));

        removeEmptySections(blocks, 2);
        removeEmptySections(blocks, 1);
        for (Block b : blocks) {
            if (b.skipped && !b.table) {
                b.removed = true;
            }
        }
        // Solo se renumera si desaparecio algun apartado; si no, se respeta la numeracion de la plantilla
        if (blocks.stream().anyMatch(b -> b.removed && b.level > 0)) {
            renumber(blocks);
        }

        var images = new java.util.ArrayList<Image>();
        StringBuilder sb = new StringBuilder();
        boolean lastWasTable = false;
        for (Block b : blocks) {
            if (b.removed) {
                continue;
            }
            // Word exige un parrafo entre una tabla final y la seccion del documento
            if (b.gap && lastWasTable && b.xml.contains("<w:sectPr")) {
                sb.append("<w:p/>");
            }
            if (!b.gap) {
                lastWasTable = b.table;
            }
            if (b.image != null) {
                Image img = new Image("localis_diagram" + (images.size() + 1) + ".png",
                        "rIdLocalisDiagram" + (images.size() + 1), b.image);
                images.add(img);
                b.xml = imageParagraph(img, images.size());
            }
            sb.append(b.xml);
        }
        return pack(docx, sb.toString(), images);
    }

    private record Image(String name, String rId, byte[] png) {}

    private static String imageParagraph(Image img, int n) throws IOException {
        var raster = ImageIO.read(new ByteArrayInputStream(img.png()));
        long cx = Math.min(5_486_400L, raster.getWidth() / 2L * 9525L);
        long cy = cx * raster.getHeight() / raster.getWidth();
        if (cy > 5_943_600L) {
            cx = cx * 5_943_600L / cy;
            cy = 5_943_600L;
        }
        return "<w:p><w:pPr><w:spacing w:before=\"120\" w:after=\"120\"/><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing>"
                + "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"" + cx + "\" cy=\"" + cy + "\"/>"
                + "<wp:docPr id=\"" + (9000 + n) + "\" name=\"Diagrama " + n + "\"/>"
                + "<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">"
                + "<a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
                + "<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
                + "<pic:nvPicPr><pic:cNvPr id=\"0\" name=\"" + img.name() + "\"/><pic:cNvPicPr/></pic:nvPicPr>"
                + "<pic:blipFill><a:blip r:embed=\"" + img.rId() + "\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>"
                + "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>"
                + "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>";
    }

    private static byte[] pack(byte[] docx, String xml, java.util.List<Image> images) throws IOException {
        if (images.isEmpty()) {
            return replaceDocumentXml(docx, xml);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (var in = new java.util.zip.ZipInputStream(new ByteArrayInputStream(docx));
                ZipOutputStream out = new ZipOutputStream(bos)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                byte[] data = in.readAllBytes();
                if (e.getName().equals("word/document.xml")) {
                    data = xml.getBytes(StandardCharsets.UTF_8);
                } else if (e.getName().equals("word/_rels/document.xml.rels")) {
                    StringBuilder rels = new StringBuilder();
                    for (Image img : images) {
                        rels.append("<Relationship Id=\"").append(img.rId())
                                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\"")
                                .append(" Target=\"media/").append(img.name()).append("\"/>");
                    }
                    data = new String(data, StandardCharsets.UTF_8).replace("</Relationships>", rels + "</Relationships>")
                            .getBytes(StandardCharsets.UTF_8);
                }
                out.putNextEntry(new ZipEntry(e.getName()));
                out.write(data);
                out.closeEntry();
            }
            for (Image img : images) {
                out.putNextEntry(new ZipEntry("word/media/" + img.name()));
                out.write(img.png());
                out.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static Block gap(String xml) {
        Block b = new Block();
        b.xml = xml;
        b.gap = true;
        return b;
    }

    private static int processParagraph(Block b, java.util.Map<Integer, String> values,
            java.util.Map<Integer, byte[]> diagrams, int idx) {
        String v = values.get(idx);
        String text = paragraphText(b.xml).trim();
        Matcher num = NUM_PREFIX.matcher(text);
        b.level = headingLevel(b.xml, text);
        if (v != null) {
            // El numero ("5.2", "1.") no lo escribe el LLM: se conserva el de la plantilla
            String prefix = num.matches() ? num.group(1) + " " : "";
            b.orig = 1;
            if (isSkip(v)) {
                b.skipped = true;
            } else {
                b.live = 1;
                b.xml = setParagraphText(b.xml, prefix + v);
            }
        } else if (DIAGRAM_BOX.matcher(text).matches()) {
            // Sin diagrama generado la caja de la plantilla se elimina
            b.image = diagrams.get(idx);
            b.skipped = b.image == null;
        } else if (text.length() >= 100) {
            b.level = 0;
        }
        return idx + 1;
    }

    private static int processTable(Block b, java.util.Map<Integer, String> values, int idx) {
        Matcher rows = ROW.matcher(b.xml);
        StringBuilder table = new StringBuilder();
        while (rows.find()) {
            int rowOrig = 0;
            int rowLive = 0;
            Matcher ps = PARAGRAPH.matcher(rows.group());
            StringBuilder row = new StringBuilder();
            while (ps.find()) {
                String p = ps.group();
                String v = values.get(idx++);
                if (v != null) {
                    rowOrig++;
                    if (isSkip(v)) {
                        p = setParagraphText(p, "-");
                    } else {
                        rowLive++;
                        p = setParagraphText(p, v);
                    }
                }
                ps.appendReplacement(row, Matcher.quoteReplacement(p));
            }
            ps.appendTail(row);
            boolean drop = rowOrig > 0 && rowLive == 0;
            b.orig += rowOrig;
            b.live += drop ? 0 : rowLive;
            rows.appendReplacement(table, Matcher.quoteReplacement(drop ? "" : row.toString()));
        }
        rows.appendTail(table);
        b.xml = table.toString();
        b.removed = b.orig > 0 && b.live == 0;
        return idx;
    }

    private static void removeEmptySections(java.util.List<Block> blocks, int level) {
        for (int i = 0; i < blocks.size(); i++) {
            if (blocks.get(i).level != level) {
                continue;
            }
            int end = i + 1;
            while (end < blocks.size() && !(blocks.get(end).level > 0 && blocks.get(end).level <= level)) {
                end++;
            }
            int orig = 0;
            int live = 0;
            for (int j = i + 1; j < end; j++) {
                orig += blocks.get(j).orig;
                live += blocks.get(j).removed ? 0 : blocks.get(j).live;
            }
            // Un titulo cuyo nombre no aplica ("Component Detail: [Module Name]") elimina todo su apartado
            if (blocks.get(i).skipped || (orig > 0 && live == 0)) {
                for (int j = i; j < end; j++) {
                    if (!blocks.get(j).gap) {
                        blocks.get(j).removed = true;
                    }
                }
            }
        }
    }

    private static void renumber(java.util.List<Block> blocks) {
        int n = 0;
        int sub = 0;
        for (Block b : blocks) {
            if (b.removed || b.level == 0) {
                continue;
            }
            String number;
            Pattern old;
            if (b.level == 1) {
                number = (++n) + ".";
                sub = 0;
                old = Pattern.compile("^(\\s*)\\d+\\.(?=\\s)");
            } else {
                number = n + "." + (++sub);
                old = Pattern.compile("^(\\s*)\\d+\\.\\d+(?=\\s)");
            }
            Matcher t = TEXT_NODE.matcher(b.xml);
            if (t.find()) {
                Matcher num = old.matcher(t.group(2));
                if (num.find()) {
                    String text = num.replaceFirst(Matcher.quoteReplacement(num.group(1) + number));
                    b.xml = b.xml.substring(0, t.start(2)) + text + b.xml.substring(t.end(2));
                }
            }
        }
    }

    private static String paragraphText(String paragraph) {
        Matcher t = TEXT_NODE.matcher(paragraph);
        StringBuilder sb = new StringBuilder();
        while (t.find()) {
            sb.append(t.group(2));
        }
        return sb.toString().replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    private static String setParagraphText(String paragraph, String value) {
        // El texto de ejemplo de las plantillas va en gris cursiva; el contenido real debe verse como texto normal.
        paragraph = paragraph.replaceAll("<w:iCs/>|<w:i/>", "")
                .replaceAll("<w:color w:val=\"(?:888888|555555|808080|999999|7F7F7F|AAAAAA)\"/>", "<w:color w:val=\"000000\"/>");
        Matcher t = TEXT_NODE.matcher(paragraph);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        while (t.find()) {
            String repl = first ? "<w:t xml:space=\"preserve\">" + escape(value) + "</w:t>" : t.group(1) + t.group(3);
            first = false;
            t.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        t.appendTail(sb);
        return sb.toString();
    }

    private static String documentXml(byte[] docx) throws IOException {
        try (var in = new java.util.zip.ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.getName().equals("word/document.xml")) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IOException("El .docx no contiene word/document.xml");
    }

    private static byte[] replaceDocumentXml(byte[] docx, String xml) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (var in = new java.util.zip.ZipInputStream(new ByteArrayInputStream(docx));
                ZipOutputStream out = new ZipOutputStream(bos)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                byte[] data = in.readAllBytes();
                if (e.getName().equals("word/document.xml")) {
                    data = xml.getBytes(StandardCharsets.UTF_8);
                }
                out.putNextEntry(new ZipEntry(e.getName()));
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
