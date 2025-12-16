package com.capability.pdfgeneration.service.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ============================================================================
 * STANDALONE FLATTEN AND CHECK TOOL
 * ============================================================================
 * 
 * This tool:
 *   1. Takes an input PDF
 *   2. Flattens it (removes transparency by rendering to images)
 *   3. Saves the flattened PDF
 *   4. Runs the transparency checker on both before and after
 * 
 * USAGE:
 *   ./mvnw compile exec:java \
 *     -Dexec.mainClass="com.capability.pdfgeneration.service.util.FlattenAndCheck" \
 *     -Dexec.args="input.pdf output-flattened.pdf"
 * 
 * Or with just input (output will be input-flattened.pdf):
 *   ./mvnw compile exec:java \
 *     -Dexec.mainClass="com.capability.pdfgeneration.service.util.FlattenAndCheck" \
 *     -Dexec.args="input.pdf"
 */
public class FlattenAndCheck {

    private static final float DEFAULT_DPI = 300f;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: FlattenAndCheck <input.pdf> [output.pdf]");
            System.out.println();
            System.out.println("Example:");
            System.out.println("  FlattenAndCheck input.pdf");
            System.out.println("  FlattenAndCheck input.pdf output-flattened.pdf");
            System.exit(1);
        }

        String inputPath = args[0];
        String outputPath = args.length > 1 ? args[1] : inputPath.replace(".pdf", "-flattened.pdf");

        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            System.err.println("ERROR: Input file not found: " + inputPath);
            System.exit(1);
        }

        try {
            System.out.println("╔══════════════════════════════════════════════════════════════╗");
            System.out.println("║          PDF TRANSPARENCY FLATTENING TOOL                    ║");
            System.out.println("╚══════════════════════════════════════════════════════════════╝");
            System.out.println();
            
            // Read input PDF
            byte[] inputBytes = Files.readAllBytes(inputFile.toPath());
            
            // Check BEFORE flattening
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("  STEP 1: Checking BEFORE flattening");
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("Input file: " + inputPath);
            System.out.println("Size: " + inputBytes.length + " bytes");
            PdfTransparencyChecker.checkTransparency(inputBytes, inputFile.getName());
            
            // Flatten the PDF
            System.out.println();
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("  STEP 2: Flattening PDF (DPI: " + DEFAULT_DPI + ")");
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("Processing...");
            
            long startTime = System.currentTimeMillis();
            byte[] flattenedBytes = flattenPdf(inputBytes, DEFAULT_DPI);
            long endTime = System.currentTimeMillis();
            
            System.out.println("Flattening completed in " + (endTime - startTime) + "ms");
            System.out.println("Original size: " + inputBytes.length + " bytes");
            System.out.println("Flattened size: " + flattenedBytes.length + " bytes");
            
            // Save flattened PDF
            Files.write(Path.of(outputPath), flattenedBytes);
            System.out.println("Saved to: " + outputPath);
            
            // Check AFTER flattening
            System.out.println();
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("  STEP 3: Checking AFTER flattening");
            System.out.println("═══════════════════════════════════════════════════════════════");
            PdfTransparencyChecker.checkTransparency(flattenedBytes, new File(outputPath).getName());
            
            System.out.println();
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("  DONE!");
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("Flattened PDF saved to: " + outputPath);
            System.out.println();
            
        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Flattens a PDF by rendering each page as an image.
     * This is the same logic as TransparencyFlatteningService but standalone.
     */
    public static byte[] flattenPdf(byte[] pdfBytes, float dpi) throws IOException {
        float scale = dpi / 72f;
        
        try (PDDocument originalDoc = Loader.loadPDF(pdfBytes);
             PDDocument newDoc = new PDDocument();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            
            PDFRenderer renderer = new PDFRenderer(originalDoc);
            int pageCount = originalDoc.getNumberOfPages();
            
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                System.out.println("  Processing page " + (pageIndex + 1) + "/" + pageCount + "...");
                
                // Render page to image (RGB = no alpha/transparency)
                BufferedImage image = renderer.renderImage(pageIndex, scale, ImageType.RGB);
                
                // Convert to JPEG
                byte[] imageBytes;
                try (ByteArrayOutputStream imgOut = new ByteArrayOutputStream()) {
                    ImageIO.write(image, "JPEG", imgOut);
                    imageBytes = imgOut.toByteArray();
                }
                
                // Get original page dimensions
                PDPage originalPage = originalDoc.getPage(pageIndex);
                PDRectangle originalSize = originalPage.getMediaBox();
                
                // Create new page with same size
                PDPage newPage = new PDPage(originalSize);
                newDoc.addPage(newPage);
                
                // Draw image on new page
                PDImageXObject pdImage = PDImageXObject.createFromByteArray(
                        newDoc, imageBytes, "page_" + pageIndex);
                
                try (PDPageContentStream contentStream = new PDPageContentStream(newDoc, newPage)) {
                    contentStream.drawImage(pdImage, 0, 0, 
                            originalSize.getWidth(), originalSize.getHeight());
                }
            }
            
            // Save the flattened PDF (keeping original version)
            // Transparency is removed, so it's compatible with older viewers
            // regardless of the version number
            newDoc.save(outputStream);
            
            return outputStream.toByteArray();
        }
    }
}

