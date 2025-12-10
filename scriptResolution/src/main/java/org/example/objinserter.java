package org.example;

import uk.org.okapibarcode.backend.Code3Of9;
import uk.org.okapibarcode.backend.HumanReadableLocation;
import uk.org.okapibarcode.graphics.Color;
import uk.org.okapibarcode.output.Java2DRenderer;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class objinserter {
    public static void main(String[] args) throws Exception {
        Code3Of9 barcode = new Code3Of9();
        barcode.setFontName("Monospaced");
        barcode.setFontSize(16);
        barcode.setModuleWidth(2);
        barcode.setBarHeight(50);
        barcode.setHumanReadableLocation(HumanReadableLocation.NONE);
        barcode.setContent("1001000");

        int width = barcode.getWidth();
        int height = barcode.getHeight();

        // Render the barcode
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2d = image.createGraphics();
        Java2DRenderer renderer = new Java2DRenderer(g2d, 1, Color.WHITE, Color.BLACK);
        renderer.render(barcode);
        g2d.dispose();

        // Rotate 90 degrees clockwise (swap width and height)
        BufferedImage rotatedImage = new BufferedImage(height, width, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2dRotated = rotatedImage.createGraphics();
        AffineTransform transform = new AffineTransform();
        transform.translate(height, 0);
        transform.rotate(Math.toRadians(90));
        g2dRotated.setTransform(transform);
        g2dRotated.drawImage(image, 0, 0, null);
        g2dRotated.dispose();

        ImageIO.write(rotatedImage, "png", new File("code3of9_2.png"));
    }
}