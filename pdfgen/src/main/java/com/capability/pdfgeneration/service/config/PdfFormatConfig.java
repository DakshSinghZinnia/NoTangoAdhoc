package com.capability.pdfgeneration.service.config;

import org.jodconverter.core.document.DefaultDocumentFormatRegistry;
import org.jodconverter.core.document.DocumentFamily;
import org.jodconverter.core.document.DocumentFormat;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for custom PDF export settings from LibreOffice.
 * 
 * ============================================================================
 * WHAT THIS FILE DOES:
 * ============================================================================
 * When LibreOffice converts DOCX to PDF, it uses "filter options" to control
 * the output. This file sets those options to:
 *   1. Use PDF/A-1b format (which is based on PDF 1.4 and has strict rules)
 *   2. Embed all fonts (so the PDF looks the same on any computer)
 *   3. Use lossless image compression (better quality)
 * 
 * ============================================================================
 * LIBREOFFICE PDF EXPORT OPTIONS EXPLAINED:
 * ============================================================================
 * 
 * SelectPdfVersion - Which PDF version/standard to use:
 *   0  = PDF 1.7 (newest, supports all features including transparency)
 *   1  = PDF/A-1b (based on PDF 1.4, archival format, often flattens transparency)
 *   2  = PDF/A-2b (based on PDF 1.7, allows transparency)
 *   3  = PDF/A-3b (PDF 1.7 + embedded files)
 *   15 = PDF 1.5
 *   16 = PDF 1.6
 * 
 * UseLosslessCompression - How to compress images:
 *   true  = PNG-like compression (no quality loss, larger files)
 *   false = JPEG compression (smaller files, some quality loss)
 * 
 * Quality - JPEG quality when UseLosslessCompression is false:
 *   1-100 (higher = better quality, larger file)
 * 
 * ReduceImageResolution - Reduce image DPI:
 *   true = downscale images to MaxImageResolution DPI
 *   false = keep original resolution
 * 
 * MaxImageResolution - Target DPI when ReduceImageResolution is true:
 *   Common values: 150 (web), 300 (print), 600 (high quality print)
 * 
 * EmbedStandardFonts - Include standard fonts in PDF:
 *   true = fonts embedded (PDF works everywhere)
 *   false = rely on system fonts (smaller file, may look different)
 * 
 * UseTaggedPDF - Add accessibility tags:
 *   true = screen readers can read the PDF
 *   false = no accessibility tags
 * 
 * ExportBookmarks - Include document outline/bookmarks:
 *   true = headings become bookmarks
 *   false = no bookmarks
 * 
 * ============================================================================
 * WHY PDF/A-1b HELPS WITH TRANSPARENCY:
 * ============================================================================
 * PDF/A-1b is an archival format with strict rules. It's based on PDF 1.4
 * and requires all content to be "flattened" for long-term preservation.
 * This means transparency effects are often converted to solid colors.
 * 
 * HOWEVER: This is NOT guaranteed to remove all transparency. For true
 * PDF 1.3 compatibility, we also use post-processing with PDFBox or Ghostscript.
 */
@Configuration
public class PdfFormatConfig {

    /**
     * Creates a PDF format configured for maximum PDF 1.3 compatibility.
     * 
     * This is a Spring "Bean" - Spring will automatically create this object
     * and make it available for other classes to use via @Autowired.
     */
    @Bean
    public DocumentFormat pdfVersion14Format() {
        // Start with LibreOffice's default PDF format settings
        DocumentFormat defaultPdf = DefaultDocumentFormatRegistry.PDF;
        
        // ====================================================================
        // FILTER DATA: These options control how LibreOffice creates the PDF
        // ====================================================================
        Map<String, Object> filterData = new LinkedHashMap<>();
        
        // Use PDF/A-1b (value=1) which is based on PDF 1.4 and has strict rules
        // This helps flatten some transparency effects
        filterData.put("SelectPdfVersion", 1);
        
        // Embed all fonts so the PDF looks the same on any computer
        filterData.put("EmbedStandardFonts", true);
        
        // Use lossless compression for images (PNG-like, no quality loss)
        filterData.put("UseLosslessCompression", true);
        
        // Don't reduce image resolution (keep original quality)
        filterData.put("ReduceImageResolution", false);
        
        // Don't add accessibility tags (simpler PDF structure)
        filterData.put("UseTaggedPDF", false);
        
        // Don't export form fields (simpler PDF)
        filterData.put("ExportFormFields", false);
        
        // Don't export bookmarks (simpler PDF)
        filterData.put("ExportBookmarks", false);
        
        // Build and return the custom PDF format
        // Note: JODConverter requires (DocumentFamily, propertyName, value) for storeProperty
        return DocumentFormat.builder()
                .from(defaultPdf)  // Start with default PDF settings
                .storeProperty(DocumentFamily.TEXT, "FilterData", filterData)  // For Writer documents
                .build();
    }
}
