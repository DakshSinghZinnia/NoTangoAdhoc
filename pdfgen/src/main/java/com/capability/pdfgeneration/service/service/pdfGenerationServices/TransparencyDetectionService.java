package com.capability.pdfgeneration.service.service.pdfGenerationServices;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * ============================================================================
 * TRANSPARENCY DETECTION SERVICE
 * ============================================================================
 * 
 * WHAT THIS SERVICE DOES:
 * -----------------------
 * This service scans a PDF file and detects ANY transparency features.
 * It checks for:
 * 
 *   1. TRANSPARENCY GROUPS (/Group with /S /Transparency)
 *      - Pages or form XObjects marked as having transparency
 * 
 *   2. SOFT MASKS (SMask)
 *      - Used for gradient transparency, feathered edges
 *      - Can be on images or in graphics states
 * 
 *   3. BLEND MODES
 *      - Normal = no blending (OK for PDF 1.3)
 *      - Multiply, Screen, Overlay, etc. = transparency feature
 * 
 *   4. ALPHA/CA VALUES
 *      - CA = stroke opacity (1.0 = fully opaque)
 *      - ca = fill opacity (1.0 = fully opaque)
 *      - Values < 1.0 indicate transparency
 * 
 *   5. IMAGES WITH ALPHA CHANNELS
 *      - PNG images with transparency
 *      - Images with soft mask (SMask) attached
 * 
 * WHY CHECK FOR TRANSPARENCY:
 * ---------------------------
 * PDF 1.3 does NOT support transparency. If you're generating PDF 1.3 files,
 * they should have NO transparency features. This service lets you verify that.
 * 
 * HOW TO USE:
 * -----------
 *   TransparencyReport report = service.detectTransparency(pdfBytes);
 *   if (report.hasTransparency()) {
 *       System.out.println("WARNING: PDF has transparency!");
 *       report.getIssues().forEach(System.out::println);
 *   } else {
 *       System.out.println("OK: PDF is transparency-free!");
 *   }
 */
@Slf4j
@Service
public class TransparencyDetectionService {

    /**
     * Result of a transparency scan.
     */
    public record TransparencyReport(
            boolean hasTransparency,
            float pdfVersion,
            int pageCount,
            List<String> issues
    ) {
        /**
         * Returns a human-readable summary of the report.
         */
        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("╔══════════════════════════════════════════════════════════════╗\n");
            sb.append("║           PDF TRANSPARENCY DETECTION REPORT                  ║\n");
            sb.append("╠══════════════════════════════════════════════════════════════╣\n");
            sb.append(String.format("║  PDF Version:    %-42s ║\n", pdfVersion));
            sb.append(String.format("║  Page Count:     %-42d ║\n", pageCount));
            sb.append(String.format("║  Has Transparency: %-40s ║\n", hasTransparency ? "⚠️  YES" : "✅ NO"));
            sb.append("╠══════════════════════════════════════════════════════════════╣\n");
            
            if (issues.isEmpty()) {
                sb.append("║  ✅ No transparency features detected!                       ║\n");
                sb.append("║     This PDF is compatible with PDF 1.3 viewers.            ║\n");
            } else {
                sb.append("║  ⚠️  TRANSPARENCY ISSUES FOUND:                              ║\n");
                sb.append("╟──────────────────────────────────────────────────────────────╢\n");
                for (String issue : issues) {
                    // Wrap long lines
                    String wrapped = issue.length() > 58 ? issue.substring(0, 55) + "..." : issue;
                    sb.append(String.format("║  • %-58s ║\n", wrapped));
                }
            }
            sb.append("╚══════════════════════════════════════════════════════════════╝");
            return sb.toString();
        }
    }

    /**
     * Scans a PDF for transparency features.
     * 
     * @param pdfBytes The PDF to scan
     * @return A report detailing any transparency found
     * @throws IOException If the PDF cannot be read
     */
    public TransparencyReport detectTransparency(byte[] pdfBytes) throws IOException {
        List<String> issues = new ArrayList<>();
        
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            float version = doc.getVersion();
            int pageCount = doc.getNumberOfPages();
            
            log.info("Scanning PDF for transparency (version: {}, pages: {})", version, pageCount);
            
            // Check 1: PDF version warning
            if (version >= 1.4f) {
                issues.add(String.format("PDF version %.1f supports transparency (1.4+)", version));
            }
            
            // Scan each page
            for (int i = 0; i < pageCount; i++) {
                PDPage page = doc.getPage(i);
                int pageNum = i + 1;
                
                // Check 2: Page-level transparency group
                COSDictionary pageDict = page.getCOSObject();
                if (hasTransparencyGroup(pageDict)) {
                    issues.add(String.format("Page %d: Has transparency group", pageNum));
                }
                
                // Check 3: Scan page resources
                PDResources resources = page.getResources();
                if (resources != null) {
                    scanResources(resources, "Page " + pageNum, issues);
                }
            }
            
            boolean hasTransparency = !issues.isEmpty() || version >= 1.4f;
            
            // If version < 1.4 and no issues found, it's clean
            if (version < 1.4f && issues.isEmpty()) {
                hasTransparency = false;
            }
            // If version >= 1.4 but no actual transparency features, just warn
            if (version >= 1.4f && issues.size() == 1 && issues.get(0).contains("PDF version")) {
                // Only the version warning, no actual transparency
                log.info("PDF is version {} but has no transparency features", version);
            }
            
            TransparencyReport report = new TransparencyReport(
                    hasTransparency && issues.stream().anyMatch(s -> !s.contains("PDF version")),
                    version,
                    pageCount,
                    issues
            );
            
            log.info("Transparency scan complete. Issues found: {}", issues.size());
            return report;
        }
    }

    /**
     * Checks if a dictionary has a transparency group.
     */
    private boolean hasTransparencyGroup(COSDictionary dict) {
        COSDictionary group = dict.getCOSDictionary(COSName.GROUP);
        if (group != null) {
            COSName subtype = group.getCOSName(COSName.S);
            return COSName.TRANSPARENCY.equals(subtype);
        }
        return false;
    }

    /**
     * Scans resources for transparency features.
     */
    private void scanResources(PDResources resources, String location, List<String> issues) {
        // Check Extended Graphics States (blend modes, opacity)
        for (COSName name : resources.getExtGStateNames()) {
            try {
                PDExtendedGraphicsState gs = resources.getExtGState(name);
                if (gs != null) {
                    checkGraphicsState(gs, location, name.getName(), issues);
                }
            } catch (Exception e) {
                log.warn("Could not check ExtGState {}: {}", name, e.getMessage());
            }
        }
        
        // Check XObjects (images and forms)
        for (COSName name : resources.getXObjectNames()) {
            try {
                PDXObject xobj = resources.getXObject(name);
                if (xobj instanceof PDImageXObject img) {
                    checkImage(img, location, name.getName(), issues);
                } else if (xobj instanceof PDFormXObject form) {
                    checkFormXObject(form, location, name.getName(), issues);
                }
            } catch (Exception e) {
                log.warn("Could not check XObject {}: {}", name, e.getMessage());
            }
        }
    }

    /**
     * Checks an extended graphics state for transparency features.
     */
    private void checkGraphicsState(PDExtendedGraphicsState gs, String location, 
                                     String gsName, List<String> issues) {
        // Check blend mode (PDFBox 3.x returns BlendMode class, not COSName)
        org.apache.pdfbox.pdmodel.graphics.blend.BlendMode blendMode = gs.getBlendMode();
        if (blendMode != null && 
            blendMode != org.apache.pdfbox.pdmodel.graphics.blend.BlendMode.NORMAL && 
            blendMode != org.apache.pdfbox.pdmodel.graphics.blend.BlendMode.COMPATIBLE) {
            issues.add(String.format("%s: Blend mode '%s' in ExtGState '%s'", 
                    location, blendMode.toString(), gsName));
        }
        
        // Check stroke opacity (CA)
        Float ca = gs.getStrokingAlphaConstant();
        if (ca != null && ca < 1.0f) {
            issues.add(String.format("%s: Stroke opacity %.2f in ExtGState '%s'", 
                    location, ca, gsName));
        }
        
        // Check fill opacity (ca)
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

    /**
     * Checks an image for transparency features.
     */
    private void checkImage(PDImageXObject img, String location, 
                           String imgName, List<String> issues) {
        // Check for soft mask (SMask) - getSoftMask() throws IOException in PDFBox 3.x
        try {
            PDImageXObject smask = img.getSoftMask();
            if (smask != null) {
                issues.add(String.format("%s: Image '%s' has soft mask (transparency)", 
                        location, imgName));
            }
        } catch (IOException e) {
            log.warn("Could not check soft mask for image {}: {}", imgName, e.getMessage());
        }
        
        // Check for mask
        COSBase mask = img.getCOSObject().getDictionaryObject(COSName.MASK);
        if (mask instanceof COSStream) {
            // Stencil mask or soft mask - indicates transparency
            issues.add(String.format("%s: Image '%s' has mask", location, imgName));
        }
        
        // Check color space for alpha
        // Note: Most transparency comes from SMask, not color space
    }

    /**
     * Checks a form XObject for transparency.
     */
    private void checkFormXObject(PDFormXObject form, String location, 
                                  String formName, List<String> issues) {
        // Check for transparency group
        COSDictionary dict = form.getCOSObject();
        if (hasTransparencyGroup(dict)) {
            issues.add(String.format("%s: Form '%s' has transparency group", 
                    location, formName));
        }
        
        // Recursively check resources
        PDResources formResources = form.getResources();
        if (formResources != null) {
            scanResources(formResources, location + "/Form:" + formName, issues);
        }
    }

    /**
     * Quick check - returns true if PDF has any transparency.
     * Use detectTransparency() for detailed report.
     */
    public boolean hasTransparency(byte[] pdfBytes) throws IOException {
        TransparencyReport report = detectTransparency(pdfBytes);
        return report.hasTransparency();
    }
}

