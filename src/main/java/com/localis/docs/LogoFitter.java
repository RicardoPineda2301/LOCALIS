package com.localis.docs;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Ajusta el logo del cliente (PNG/JPG/GIF/BMP) al lienzo del logo de la plantilla sin deformarlo. */
public final class LogoFitter {

    private static final double MARGIN = 0.10;

    private LogoFitter() {}

    public static byte[] fitToCanvas(byte[] logo, int canvasW, int canvasH) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(logo));
        if (src == null) {
            throw new IllegalArgumentException("Formato de logo no soportado (use PNG, JPG, GIF o BMP)");
        }
        double maxW = canvasW * (1 - 2 * MARGIN);
        double maxH = canvasH * (1 - 2 * MARGIN);
        double scale = Math.min(maxW / src.getWidth(), maxH / src.getHeight());
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));

        BufferedImage out = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, (canvasW - w) / 2, (canvasH - h) / 2, w, h, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(out, "png", bos);
        return bos.toByteArray();
    }
}
