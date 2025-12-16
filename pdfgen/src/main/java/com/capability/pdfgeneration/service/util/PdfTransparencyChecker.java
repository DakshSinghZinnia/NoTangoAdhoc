package com.capability.pdfgeneration.service.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================================
 * STANDALONE PDF TRANSPARENCY CHECKER
 * ============================================================================
 * 
 * A command-line tool to check if a PDF has transparency.
 * This can be run directly without starting the Spring Boot application.
 * 
 * USAGE:
 * ------
 * 
 * Option 1: Run with Maven (from the pdfgen directory):
 *   ./mvnw exec:java -Dexec.mainClass="com.capability.pdfgeneration.service.util.PdfTransparencyChecker" -Dexec.args="path/to/your.pdf"
 * 
 * Option 2: After building, run with java:
 *   java -cp target/classes:target/dependency/* com.capability.pdfgeneration.service.util.PdfTransparencyChecker path/to/your.pdf
 * 
 * Option 3: From IDE:
 *   Right-click this file -> Run 'PdfTransparencyChecker.main()'
 *   Set program arguments to your PDF path
 * 
 * EXAMPLE OUTPUT:
 * ---------------
 * 
 * ╔══════════════════════════════════════════════════════════════╗
 * ║           PDF TRANSPARENCY DETECTION REPORT                  ║
 * ╠══════════════════════════════════════════════════════════════╣
 * ║  PDF Version:    1.3                                         ║
 * ║  Page Count:     3                                           ║
 * ║  Has Transparency: ✅ NO                                     ║
 * ╠══════════════════════════════════════════════════════════════╣
 * ║  ✅ No transparency features detected!                       ║
 * ║     This PDF is compatible with PDF 1.3 viewers.             ║
 * ╚══════════════════════════════════════════════════════════════╝
 */
public class PdfTransparencyChecker {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: PdfTransparencyChecker <pdf-file-path>");
            System.out.println();
            System.out.println("Example:");
            System.out.println("  java PdfTransparencyChecker output.pdf");
            System.out.println("  java PdfTransparencyChecker /path/to/document.pdf");
            System.out.println();
            System.out.println("Or with Maven:");
            System.out.println("  ./mvnw exec:java -Dexec.mainClass=\"com.capability.pdfgeneration.service.util.PdfTransparencyChecker\" -Dexec.args=\"output.pdf\"");
            System.exit(1);
        }

        String filePath = args[0];
        File file = new File(filePath);

        if (!file.exists()) {
            System.err.println("ERROR: File not found: " + filePath);
            System.exit(1);
        }

        try {
            byte[] pdfBytes = Files.readAllBytes(file.toPath());
            checkTransparency(pdfBytes, file.getName());
        } catch (Exception e) {
            System.err.println("ERROR: Failed to read/analyze PDF: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static void checkTransparency(byte[] pdfBytes, String fileName) throws IOException {
        List<String> issues = new ArrayList<>();

        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            float version = doc.getVersion();
            int pageCount = doc.getNumberOfPages();

            System.out.println();
            System.out.println("Analyzing: " + fileName);
            System.out.println("━".repeat(60));

            // Check PDF version
            if (version >= 1.4f) {
                issues.add(String.format("PDF version %.1f supports transparency (1.4+)", version));
            }

            // Scan each page
            for (int i = 0; i < pageCount; i++) {
                PDPage page = doc.getPage(i);
                int pageNum = i + 1;
                System.out.printf("Scanning page %d/%d...%n", pageNum, pageCount);

                // Check page-level transparency group
                COSDictionary pageDict = page.getCOSObject();
                if (hasTransparencyGroup(pageDict)) {
                    issues.add(String.format("Page %d: Has transparency group", pageNum));
                }

                // Check page resources
                PDResources resources = page.getResources();
                if (resources != null) {
                    scanResources(resources, "Page " + pageNum, issues);
                }
            }

            // Determine if truly transparent
            boolean hasActualTransparency = issues.stream()
                    .anyMatch(s -> !s.contains("PDF version"));

            // Print report
            printReport(version, pageCount, issues, hasActualTransparency);
        }
    }

    private static void printReport(float version, int pageCount, 
                                    List<String> issues, boolean hasTransparency) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║           PDF TRANSPARENCY DETECTION REPORT                  ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  PDF Version:      %-42.1f ║%n", version);
        System.out.printf("║  Page Count:       %-42d ║%n", pageCount);
        System.out.printf("║  Has Transparency: %-42s ║%n", hasTransparency ? "⚠️  YES" : "✅ NO");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");

        if (issues.isEmpty()) {
            System.out.println("║  ✅ No transparency features detected!                       ║");
            System.out.println("║     This PDF is compatible with PDF 1.3 viewers.            ║");
        } else if (!hasTransparency) {
            System.out.println("║  ✅ PDF uses newer version but has NO transparency!         ║");
            System.out.println("║     Compatible with PDF 1.3 viewers.                        ║");
            System.out.println("╟──────────────────────────────────────────────────────────────╢");
            System.out.println("║  Note: Version header could be changed to 1.3               ║");
        } else {
            System.out.println("║  ⚠️  TRANSPARENCY ISSUES FOUND:                              ║");
            System.out.println("╟──────────────────────────────────────────────────────────────╢");
            for (String issue : issues) {
                String wrapped = issue.length() > 56 ? issue.substring(0, 53) + "..." : issue;
                System.out.printf("║  • %-58s ║%n", wrapped);
            }
        }
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private static boolean hasTransparencyGroup(COSDictionary dict) {
        COSDictionary group = dict.getCOSDictionary(COSName.GROUP);
        if (group != null) {
            COSName subtype = group.getCOSName(COSName.S);
            return COSName.TRANSPARENCY.equals(subtype);
        }
        return false;
    }

    private static void scanResources(PDResources resources, String location, List<String> issues) {
        // Check Extended Graphics States
        for (COSName name : resources.getExtGStateNames()) {
            try {
                PDExtendedGraphicsState gs = resources.getExtGState(name);
                if (gs != null) {
                    checkGraphicsState(gs, location, name.getName(), issues);
                }
            } catch (Exception e) {
                // Skip unreadable graphics states
            }
        }

        // Check XObjects
        for (COSName name : resources.getXObjectNames()) {
            try {
                PDXObject xobj = resources.getXObject(name);
                if (xobj instanceof PDImageXObject img) {
                    checkImage(img, location, name.getName(), issues);
                } else if (xobj instanceof PDFormXObject form) {
                    checkFormXObject(form, location, name.getName(), issues);
                }
            } catch (Exception e) {
                // Skip unreadable XObjects
            }
        }
    }

    private static void checkGraphicsState(PDExtendedGraphicsState gs, String location,
                                           String gsName, List<String> issues) {
        // Check blend mode (PDFBox 3.x returns BlendMode class, not COSName)
        org.apache.pdfbox.pdmodel.graphics.blend.BlendMode blendMode = gs.getBlendMode();
        if (blendMode != null && 
                blendMode != org.apache.pdfbox.pdmodel.graphics.blend.BlendMode.NORMAL &&
                blendMode != org.apache.pdfbox.pdmodel.graphics.blend.BlendMode.COMPATIBLE) {
            issues.add(String.format("%s: Blend mode '%s' in ExtGState '%s'",
                    location, blendMode.toString(), gsName));
        }

        // Check stroke opacity
        Float ca = gs.getStrokingAlphaConstant();
        if (ca != null && ca < 1.0f) {
            issues.add(String.format("%s: Stroke opacity %.2f in ExtGState '%s'",
                    location, ca, gsName));
        }

        // Check fill opacity
        Float nonStrokingCa = gs.getNonStrokingAlphaConstant();
        if (nonStrokingCa != null && nonStrokingCa < 1.0f) {
            issues.add(String.format("%s: Fill opacity %.2f in ExtGState '%s'",
                    location, nonStrokingCa, gsName));
        }

        // Check soft mask
        COSBase smask = gs.getCOSObject().getDictionaryObject(COSName.SMASK);
        if (smask != null && !COSName.NONE.equals(smask)) {
            issues.add(String.format("%s: Soft mask in ExtGState '%s'", location, gsName));
        }
    }

    private static void checkImage(PDImageXObject img, String location,
                                   String imgName, List<String> issues) {
        // Check for soft mask - getSoftMask() throws IOException in PDFBox 3.x
        try {
            PDImageXObject smask = img.getSoftMask();
            if (smask != null) {
                issues.add(String.format("%s: Image '%s' has soft mask (transparency)",
                        location, imgName));
            }
        } catch (IOException e) {
            // Silently skip if we can't check soft mask
        }

        // Check for mask
        COSBase mask = img.getCOSObject().getDictionaryObject(COSName.MASK);
        if (mask instanceof COSStream) {
            issues.add(String.format("%s: Image '%s' has mask", location, imgName));
        }
    }

    private static void checkFormXObject(PDFormXObject form, String location,
                                         String formName, List<String> issues) {
        // Check for transparency group
        if (hasTransparencyGroup(form.getCOSObject())) {
            issues.add(String.format("%s: Form '%s' has transparency group",
                    location, formName));
        }

        // Recursively check resources
        PDResources formResources = form.getResources();
        if (formResources != null) {
            scanResources(formResources, location + "/Form:" + formName, issues);
        }
    }
}

