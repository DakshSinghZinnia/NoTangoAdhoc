package com.capability.pdfgeneration.service.util;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ============================================================================
 * CONVERT TO PDF 1.3 TOOL (using Ghostscript)
 * ============================================================================
 * 
 * This tool converts any PDF to PDF version 1.3 using Ghostscript.
 * 
 * WHY PDF 1.3:
 * - PDF 1.3 does NOT support transparency (it was added in 1.4)
 * - Converting to 1.3 automatically flattens all transparency
 * - Text remains searchable (unlike rasterization)
 * - File size is typically smaller
 * 
 * PREREQUISITES:
 * - Ghostscript must be installed
 *   macOS: brew install ghostscript
 *   Linux: sudo apt install ghostscript
 * 
 * USAGE:
 *   ./mvnw compile exec:java \
 *     -Dexec.mainClass="com.capability.pdfgeneration.service.util.ConvertToPdf13" \
 *     -Dexec.args="input.pdf output-pdf13.pdf"
 */
public class ConvertToPdf13 {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: ConvertToPdf13 <input.pdf> [output.pdf]");
            System.out.println();
            System.out.println("Converts a PDF to version 1.3 using Ghostscript.");
            System.out.println("This automatically flattens transparency while keeping text searchable.");
            System.out.println();
            System.out.println("Example:");
            System.out.println("  ConvertToPdf13 input.pdf");
            System.out.println("  ConvertToPdf13 input.pdf output-pdf13.pdf");
            System.exit(1);
        }

        String inputPath = args[0];
        String outputPath = args.length > 1 ? args[1] : inputPath.replace(".pdf", "-pdf13.pdf");

        File inputFile = new File(inputPath);
        if (!inputFile.exists()) {
            System.err.println("ERROR: Input file not found: " + inputPath);
            System.exit(1);
        }

        try {
            System.out.println("╔══════════════════════════════════════════════════════════════╗");
            System.out.println("║          CONVERT TO PDF 1.3 (Ghostscript)                    ║");
            System.out.println("╚══════════════════════════════════════════════════════════════╝");
            System.out.println();

            // Check BEFORE conversion
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("  STEP 1: Checking BEFORE conversion");
            System.out.println("═══════════════════════════════════════════════════════════════");
            byte[] inputBytes = Files.readAllBytes(inputFile.toPath());
            System.out.println("Input file: " + inputPath);
            System.out.println("Size: " + inputBytes.length + " bytes");
            PdfTransparencyChecker.checkTransparency(inputBytes, inputFile.getName());

            // Convert using Ghostscript
            System.out.println();
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("  STEP 2: Converting to PDF 1.3 using Ghostscript");
            System.out.println("═══════════════════════════════════════════════════════════════");
            
            long startTime = System.currentTimeMillis();
            byte[] outputBytes = convertToPdf13(inputPath, outputPath);
            long endTime = System.currentTimeMillis();

            System.out.println("Conversion completed in " + (endTime - startTime) + "ms");
            System.out.println("Original size: " + inputBytes.length + " bytes");
            System.out.println("PDF 1.3 size: " + outputBytes.length + " bytes");
            System.out.println("Saved to: " + outputPath);

            // Check AFTER conversion
            System.out.println();
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("  STEP 3: Checking AFTER conversion");
            System.out.println("═══════════════════════════════════════════════════════════════");
            PdfTransparencyChecker.checkTransparency(outputBytes, new File(outputPath).getName());

            System.out.println();
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("  DONE!");
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("PDF 1.3 saved to: " + outputPath);
            System.out.println();
            System.out.println("Benefits of PDF 1.3 conversion:");
            System.out.println("  ✓ No transparency (automatically flattened)");
            System.out.println("  ✓ Text is still searchable");
            System.out.println("  ✓ Smaller file size than rasterization");
            System.out.println();

        } catch (Exception e) {
            System.err.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Converts a PDF to version 1.3 using Ghostscript.
     * 
     * Ghostscript command:
     *   gs -dNOPAUSE -dBATCH -sDEVICE=pdfwrite -dCompatibilityLevel=1.3 \
     *      -sOutputFile=output.pdf input.pdf
     * 
     * -dCompatibilityLevel=1.3 is the key - it forces PDF 1.3 output,
     * which automatically flattens transparency since 1.3 doesn't support it.
     */
    public static byte[] convertToPdf13(String inputPath, String outputPath) throws Exception {
        // Find Ghostscript
        String gs = findGhostscript();
        if (gs == null) {
            throw new RuntimeException("Ghostscript not found! Install with: brew install ghostscript");
        }
        
        System.out.println("Using Ghostscript: " + gs);
        System.out.println("Converting...");

        // Build command
        ProcessBuilder pb = new ProcessBuilder(
            gs,
            "-dNOPAUSE",                    // Don't pause between pages
            "-dBATCH",                      // Exit when done
            "-dSAFER",                      // Restrict file operations
            "-sDEVICE=pdfwrite",            // Output as PDF
            "-dCompatibilityLevel=1.3",    // ★ Force PDF 1.3 ★
            "-dPDFSETTINGS=/prepress",     // High quality settings
            "-dColorConversionStrategy=/LeaveColorUnchanged",
            "-dDownsampleMonoImages=false",
            "-dDownsampleGrayImages=false",
            "-dDownsampleColorImages=false",
            "-dAutoRotatePages=/None",      // Don't rotate pages
            "-sOutputFile=" + outputPath,
            inputPath
        );

        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Read output
        String output;
        try (InputStream is = process.getInputStream()) {
            output = new String(is.readAllBytes());
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            System.err.println("Ghostscript output: " + output);
            throw new RuntimeException("Ghostscript failed with exit code " + exitCode);
        }

        // Read the output file
        return Files.readAllBytes(Path.of(outputPath));
    }

    /**
     * Finds the Ghostscript executable.
     */
    private static String findGhostscript() {
        String[] paths = {
            "gs",                                    // In PATH
            "/opt/homebrew/bin/gs",                  // macOS (Apple Silicon)
            "/usr/local/bin/gs",                     // macOS (Intel)
            "/usr/bin/gs",                           // Linux
            "C:\\Program Files\\gs\\gs10.02.0\\bin\\gswin64c.exe"  // Windows
        };

        for (String path : paths) {
            try {
                ProcessBuilder pb = new ProcessBuilder(path, "--version");
                Process p = pb.start();
                if (p.waitFor() == 0) {
                    return path;
                }
            } catch (Exception e) {
                // Try next path
            }
        }
        return null;
    }
}

