package com.capability.pdfgeneration.service.service.pdfGenerationServices;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ============================================================================
 * PDF VERSION SERVICE
 * ============================================================================
 * 
 * WHAT THIS SERVICE DOES:
 * -----------------------
 * This service provides methods to:
 *   1. Change the PDF version header (e.g., from 1.7 to 1.3)
 *   2. Flatten transparency using Ghostscript (external tool)
 *   3. Check what version a PDF currently is
 * 
 * ============================================================================
 * PDF VERSION HISTORY:
 * ============================================================================
 * 
 *   VERSION | YEAR | MAJOR FEATURES ADDED
 *   --------|------|----------------------------------------------------
 *   1.0     | 1993 | Basic PDF (text, images, graphics)
 *   1.1     | 1994 | Passwords, device-independent colors
 *   1.2     | 1996 | Forms, Unicode, OPI
 *   1.3     | 1999 | Digital signatures, JavaScript, FDF
 *   1.4     | 2001 | ★ TRANSPARENCY ★, JBIG2, metadata
 *   1.5     | 2003 | Object streams, JPEG2000
 *   1.6     | 2005 | 3D, AES encryption
 *   1.7     | 2006 | ISO 32000, XFA forms
 * 
 * KEY INSIGHT: Transparency was added in PDF 1.4. This is why PDF 1.3
 * cannot have transparency - the feature didn't exist yet!
 * 
 * ============================================================================
 * IMPORTANT WARNING:
 * ============================================================================
 * 
 * Simply changing the version header does NOT remove features!
 * 
 * Example: If you have a PDF 1.7 with transparency and change the header
 * to "1.3", the transparency data is still in the file. Old PDF readers
 * that strictly enforce 1.3 may:
 *   - Crash
 *   - Show blank areas
 *   - Display incorrectly
 * 
 * To truly get PDF 1.3 compatibility, you must:
 *   1. Change the version header (this service)
 *   2. ALSO flatten transparency (TransparencyFlatteningService or Ghostscript)
 */
@Slf4j
@Service
public class PdfVersionService {

    /**
     * Path to Ghostscript executable.
     * Can be configured in application.properties with: ghostscript.path=/path/to/gs
     * 
     * Default values:
     *   - macOS: /opt/homebrew/bin/gs or /usr/local/bin/gs
     *   - Linux: /usr/bin/gs
     *   - Windows: C:\Program Files\gs\gs10.02.0\bin\gswin64c.exe
     */
    @Value("${ghostscript.path:gs}")
    private String ghostscriptPath;

    /**
     * Whether to use Ghostscript for flattening (requires gs to be installed).
     * Configure in application.properties: ghostscript.enabled=true
     */
    @Value("${ghostscript.enabled:false}")
    private boolean ghostscriptEnabled;

    // ========================================================================
    // METHOD 1: Simple Version Change (header only)
    // ========================================================================
    
    /**
     * Sets the PDF version header to 1.3.
     * 
     * ⚠️ WARNING: This ONLY changes the version number in the file header.
     * It does NOT remove transparency or other 1.4+ features.
     * 
     * WHAT HAPPENS:
     * 1. PDFBox opens the PDF
     * 2. Changes the header from "%PDF-1.7" to "%PDF-1.3"
     * 3. Saves the PDF
     * 
     * USE THIS WHEN:
     * - You've already flattened transparency with another method
     * - Your PDF doesn't contain any 1.4+ features
     * - You just need to change the version number for compliance
     * 
     * @param pdfBytes The input PDF bytes
     * @return PDF with version header changed to 1.3
     */
    public byte[] downgradeTo13(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            
            log.info("Changing PDF version from {} to 1.3", doc.getVersion());
            doc.setVersion(1.3f);
            doc.save(out);
            
            return out.toByteArray();
        }
    }

    /**
     * Sets the PDF version header to 1.4.
     * 
     * PDF 1.4 supports transparency, so this is useful when you want to
     * keep transparency but use a lower version than 1.7.
     * 
     * @param pdfBytes The input PDF
     * @return PDF with version header set to 1.4
     */
    public byte[] downgradeTo14(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            
            log.info("Changing PDF version from {} to 1.4", doc.getVersion());
            doc.setVersion(1.4f);
            doc.save(out);
            
            return out.toByteArray();
        }
    }

    // ========================================================================
    // METHOD 2: Ghostscript Flattening (external tool)
    // ========================================================================

    /**
     * Uses Ghostscript to flatten transparency AND set version to 1.3.
     * 
     * ⭐ THIS IS THE BEST METHOD for true PDF 1.3 compliance because:
     *   - Ghostscript truly converts/removes transparency
     *   - Text remains searchable (unlike rasterization)
     *   - Fonts are preserved
     *   - File size is often smaller than rasterized version
     * 
     * PREREQUISITES:
     * 1. Ghostscript must be installed on the system
     *    - macOS: brew install ghostscript
     *    - Ubuntu: sudo apt install ghostscript
     *    - Windows: Download from ghostscript.com
     * 
     * 2. Configure path in application.properties:
     *    ghostscript.path=/usr/bin/gs
     *    ghostscript.enabled=true
     * 
     * HOW IT WORKS:
     * 1. Write input PDF to temp file
     * 2. Run Ghostscript with these options:
     *    -dNOPAUSE          : Don't pause between pages
     *    -dBATCH            : Exit after processing
     *    -sDEVICE=pdfwrite  : Output as PDF
     *    -dCompatibilityLevel=1.3 : Target PDF 1.3
     *    -dPDFSETTINGS=/prepress : High quality settings
     *    -sOutputFile=out.pdf : Output file
     *    input.pdf          : Input file
     * 3. Read output PDF
     * 4. Clean up temp files
     * 
     * @param pdfBytes The input PDF (may have transparency)
     * @return Flattened PDF 1.3 with no transparency
     * @throws IOException If Ghostscript fails or is not installed
     */
    public byte[] flattenWithGhostscript(byte[] pdfBytes) throws IOException {
        if (!ghostscriptEnabled) {
            throw new IllegalStateException(
                "Ghostscript is not enabled. Set ghostscript.enabled=true in application.properties");
        }

        // Create temp files for input and output
        Path inputFile = Files.createTempFile("gs-input-", ".pdf");
        Path outputFile = Files.createTempFile("gs-output-", ".pdf");
        
        try {
            // Write input PDF to temp file
            Files.write(inputFile, pdfBytes);
            
            // Build Ghostscript command
            // Each option explained:
            ProcessBuilder pb = new ProcessBuilder(
                ghostscriptPath,
                "-dNOPAUSE",                    // Don't wait for user input
                "-dBATCH",                      // Exit when done
                "-dSAFER",                      // Restrict file operations (security)
                "-sDEVICE=pdfwrite",            // Output format: PDF
                "-dCompatibilityLevel=1.3",    // ★ Target PDF 1.3 ★
                "-dPDFSETTINGS=/prepress",     // High quality (alternatives: /screen, /ebook, /printer)
                "-dColorConversionStrategy=/LeaveColorUnchanged",  // Keep original colors
                "-dDownsampleMonoImages=false", // Don't reduce image quality
                "-dDownsampleGrayImages=false",
                "-dDownsampleColorImages=false",
                "-sOutputFile=" + outputFile.toAbsolutePath(),
                inputFile.toAbsolutePath().toString()
            );
            
            // Redirect error stream to see any Ghostscript messages
            pb.redirectErrorStream(true);
            
            log.info("Running Ghostscript: {}", String.join(" ", pb.command()));
            
            // Run Ghostscript
            Process process = pb.start();
            
            // Read any output (for debugging)
            String output;
            try (InputStream is = process.getInputStream()) {
                output = new String(is.readAllBytes());
            }
            
            // Wait for completion
            int exitCode = process.waitFor();
            
            if (exitCode != 0) {
                log.error("Ghostscript failed with exit code {}: {}", exitCode, output);
                throw new IOException("Ghostscript failed with exit code " + exitCode + ": " + output);
            }
            
            log.info("Ghostscript completed successfully");
            
            // Read the output PDF
            return Files.readAllBytes(outputFile);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Ghostscript was interrupted", e);
        } finally {
            // Clean up temp files
            try { Files.deleteIfExists(inputFile); } catch (IOException ignore) {}
            try { Files.deleteIfExists(outputFile); } catch (IOException ignore) {}
        }
    }

    // ========================================================================
    // UTILITY METHODS
    // ========================================================================

    /**
     * Gets the current PDF version from a document.
     * 
     * @param pdfBytes The PDF to check
     * @return The PDF version (e.g., 1.3, 1.4, 1.5, 1.7)
     */
    public float getVersion(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            return doc.getVersion();
        }
    }

    /**
     * Checks if Ghostscript is available and working.
     * 
     * @return true if Ghostscript can be executed
     */
    public boolean isGhostscriptAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(ghostscriptPath, "--version");
            Process p = pb.start();
            int exitCode = p.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            log.warn("Ghostscript not available: {}", e.getMessage());
            return false;
        }
    }
}
