package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import uk.org.okapibarcode.backend.Code3Of9;
import uk.org.okapibarcode.backend.HumanReadableLocation;
import uk.org.okapibarcode.graphics.Color;
import uk.org.okapibarcode.output.Java2DRenderer;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import javax.imageio.ImageIO;

public class ObjInserter {
    public static void main(String[] args) throws Exception {
        // Read barcode value from output.json
        ObjectMapper mapper = new ObjectMapper();
        String barcodeValue;
        
        // Try classpath first, then filesystem
        InputStream in = ObjInserter.class.getResourceAsStream("/output/output.json");
        if (in != null) {
            JsonNode root = mapper.readTree(in);
            barcodeValue = root.path("Barcode").path("value").asText();
            in.close();
        } else {
            // Try filesystem path (run from base_dir, so include scriptResolution prefix)
            File jsonFile = new File("scriptResolution/src/main/resources/output/output.json");
            JsonNode root = mapper.readTree(jsonFile);
            barcodeValue = root.path("Barcode").path("value").asText();
        }
        
        // Remove leading and trailing asterisks (Code 3 of 9 adds them automatically)
        if (barcodeValue.startsWith("*")) {
            barcodeValue = barcodeValue.substring(1);
        }
        if (barcodeValue.endsWith("*")) {
            barcodeValue = barcodeValue.substring(0, barcodeValue.length() - 1);
        }
        
        System.out.println("Barcode value from JSON: " + barcodeValue);
        
        Code3Of9 barcode = new Code3Of9();
        barcode.setFontName("Monospaced");
        barcode.setFontSize(16);
        barcode.setModuleWidth(2);
        barcode.setBarHeight(50);
        barcode.setHumanReadableLocation(HumanReadableLocation.NONE);
        barcode.setContent(barcodeValue);

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

        ImageIO.write(rotatedImage, "jpg", new File("scriptResolution/src/main/resources/objectInserter/sample_barcode.jpg"));
    }
}
