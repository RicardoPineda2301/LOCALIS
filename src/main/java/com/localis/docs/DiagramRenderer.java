package com.localis.docs;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Dibuja un diagrama de cajas y flechas (nodos y relaciones) como PNG, sin herramientas externas.
 * Los nodos se ordenan en capas segun las relaciones: de izquierda a derecha, o de arriba abajo si hay muchas capas.
 */
public final class DiagramRenderer {

    private static final int NODE_W = 190;
    private static final int NODE_H = 62;
    private static final int GAP_LAYER = 110;
    private static final int GAP_NODE = 26;
    private static final int PAD = 24;
    private static final int SCALE = 2;
    private static final int MAX_NODES = 12;
    private static final Color BORDER = new Color(0x3A, 0xAF, 0xA9);
    private static final Color FILL = new Color(0xE8, 0xF6, 0xF5);

    private record Node(String id, String label) {}

    private record Edge(String from, String to, String label) {}

    private DiagramRenderer() {}

    /** @return el PNG, o null si la especificacion no contiene un diagrama utilizable. */
    public static byte[] render(JsonNode spec) throws IOException {
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (JsonNode n : spec.path("nodes")) {
            String id = n.path("id").asText("").trim();
            String label = n.path("label").asText("").trim();
            if (!id.isEmpty() && !label.isEmpty() && nodes.size() < MAX_NODES) {
                nodes.putIfAbsent(id, new Node(id, label));
            }
        }
        if (nodes.size() < 2) {
            return null;
        }
        List<Edge> edges = new ArrayList<>();
        for (JsonNode e : spec.path("edges")) {
            String from = e.path("from").asText("");
            String to = e.path("to").asText("");
            if (nodes.containsKey(from) && nodes.containsKey(to) && !from.equals(to) && edges.size() < 24) {
                edges.add(new Edge(from, to, e.path("label").asText("").trim()));
            }
        }

        Map<String, Integer> layer = layers(nodes, edges);
        int layerCount = layer.values().stream().max(Integer::compare).orElse(0) + 1;
        List<List<Node>> columns = new ArrayList<>();
        for (int i = 0; i < layerCount; i++) {
            columns.add(new ArrayList<>());
        }
        for (Node n : nodes.values()) {
            columns.get(layer.get(n.id())).add(n);
        }
        boolean horizontal = layerCount <= 4;
        int maxInLayer = columns.stream().mapToInt(List::size).max().orElse(1);

        int w;
        int h;
        if (horizontal) {
            w = PAD * 2 + layerCount * NODE_W + (layerCount - 1) * GAP_LAYER;
            h = PAD * 2 + maxInLayer * NODE_H + (maxInLayer - 1) * GAP_NODE;
        } else {
            w = PAD * 2 + maxInLayer * NODE_W + (maxInLayer - 1) * GAP_NODE;
            h = PAD * 2 + layerCount * NODE_H + (layerCount - 1) * GAP_LAYER;
        }

        Map<String, int[]> pos = new LinkedHashMap<>();
        for (int c = 0; c < layerCount; c++) {
            List<Node> col = columns.get(c);
            for (int r = 0; r < col.size(); r++) {
                int x;
                int y;
                if (horizontal) {
                    int colH = col.size() * NODE_H + (col.size() - 1) * GAP_NODE;
                    x = PAD + c * (NODE_W + GAP_LAYER);
                    y = (h - colH) / 2 + r * (NODE_H + GAP_NODE);
                } else {
                    int rowW = col.size() * NODE_W + (col.size() - 1) * GAP_NODE;
                    x = (w - rowW) / 2 + r * (NODE_W + GAP_NODE);
                    y = PAD + c * (NODE_H + GAP_LAYER);
                }
                pos.put(col.get(r).id(), new int[] {x, y});
            }
        }

        BufferedImage img = new BufferedImage(w * SCALE, h * SCALE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.scale(SCALE, SCALE);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);

            Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 15);
            Font small = new Font(Font.SANS_SERIF, Font.ITALIC, 12);
            for (Edge e : edges) {
                drawEdge(g, pos.get(e.from()), pos.get(e.to()), e.label(), small);
            }
            g.setFont(font);
            for (Node n : nodes.values()) {
                int[] p = pos.get(n.id());
                g.setColor(FILL);
                g.fillRoundRect(p[0], p[1], NODE_W, NODE_H, 14, 14);
                g.setColor(BORDER);
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(p[0], p[1], NODE_W, NODE_H, 14, 14);
                g.setColor(new Color(0x2B, 0x2B, 0x2B));
                drawCentered(g, n.label(), p[0], p[1], NODE_W, NODE_H);
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** Capa de cada nodo = camino mas largo desde un origen; los ciclos se cortan tras n pasadas. */
    private static Map<String, Integer> layers(Map<String, Node> nodes, List<Edge> edges) {
        Map<String, Integer> layer = new LinkedHashMap<>();
        nodes.keySet().forEach(id -> layer.put(id, 0));
        for (int pass = 0; pass < nodes.size(); pass++) {
            boolean changed = false;
            for (Edge e : edges) {
                int want = layer.get(e.from()) + 1;
                if (want > layer.get(e.to()) && want < nodes.size()) {
                    layer.put(e.to(), want);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        // Compacta capas vacias
        List<Integer> used = layer.values().stream().distinct().sorted().toList();
        layer.replaceAll((k, v) -> used.indexOf(v));
        return layer;
    }

    private static void drawEdge(Graphics2D g, int[] a, int[] b, String label, Font font) {
        double ax = a[0] + NODE_W / 2.0;
        double ay = a[1] + NODE_H / 2.0;
        double bx = b[0] + NODE_W / 2.0;
        double by = b[1] + NODE_H / 2.0;
        double[] s = clip(ax, ay, bx, by);
        double[] t = clip(bx, by, ax, ay);
        g.setColor(new Color(0x55, 0x55, 0x55));
        g.setStroke(new BasicStroke(1.6f));
        g.drawLine((int) s[0], (int) s[1], (int) t[0], (int) t[1]);
        double ang = Math.atan2(t[1] - s[1], t[0] - s[0]);
        int[] xs = {(int) t[0], (int) (t[0] - 11 * Math.cos(ang - 0.4)), (int) (t[0] - 11 * Math.cos(ang + 0.4))};
        int[] ys = {(int) t[1], (int) (t[1] - 11 * Math.sin(ang - 0.4)), (int) (t[1] - 11 * Math.sin(ang + 0.4))};
        g.fillPolygon(xs, ys, 3);
        if (!label.isEmpty()) {
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(label);
            int mx = (int) ((s[0] + t[0]) / 2 - tw / 2.0);
            int my = (int) ((s[1] + t[1]) / 2);
            g.setColor(Color.WHITE);
            g.fillRect(mx - 3, my - fm.getAscent() + 2, tw + 6, fm.getHeight());
            g.setColor(new Color(0x44, 0x44, 0x44));
            g.drawString(label, mx, my + 2);
        }
    }

    /** Punto donde la recta que sale del centro (cx,cy) hacia (tx,ty) abandona el rectangulo del nodo. */
    private static double[] clip(double cx, double cy, double tx, double ty) {
        double dx = tx - cx;
        double dy = ty - cy;
        double k = Math.min(dx == 0 ? Double.MAX_VALUE : (NODE_W / 2.0) / Math.abs(dx),
                dy == 0 ? Double.MAX_VALUE : (NODE_H / 2.0) / Math.abs(dy));
        return new double[] {cx + dx * k, cy + dy * k};
    }

    private static void drawCentered(Graphics2D g, String text, int x, int y, int w, int h) {
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = wrap(text, fm, w - 16);
        int total = lines.size() * fm.getHeight();
        int ty = y + (h - total) / 2 + fm.getAscent();
        for (String line : lines) {
            g.drawString(line, x + (w - fm.stringWidth(line)) / 2, ty);
            ty += fm.getHeight();
        }
    }

    private static List<String> wrap(String text, FontMetrics fm, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String next = cur.isEmpty() ? word : cur + " " + word;
            if (fm.stringWidth(next) > maxWidth && !cur.isEmpty()) {
                lines.add(cur.toString());
                cur = new StringBuilder(word);
            } else {
                cur = new StringBuilder(next);
            }
        }
        lines.add(cur.toString());
        if (lines.size() > 3) {
            List<String> cut = new ArrayList<>(lines.subList(0, 3));
            cut.set(2, cut.get(2) + "…");
            return cut;
        }
        return lines;
    }
}
