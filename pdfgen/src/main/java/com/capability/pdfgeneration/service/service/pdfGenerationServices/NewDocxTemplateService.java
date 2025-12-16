package com.capability.pdfgeneration.service.service.pdfGenerationServices;

import com.capability.pdfgeneration.service.models.pdfGeneration.RenderResult;
import com.deepoove.poi.XWPFTemplate;
import com.deepoove.poi.config.Configure;
import com.deepoove.poi.data.PictureRenderData;
import com.deepoove.poi.data.Pictures;
import com.deepoove.poi.data.TextRenderData;
import com.deepoove.poi.data.Texts;
import com.deepoove.poi.data.style.Style;
import org.apache.poi.xwpf.usermodel.UnderlinePatterns;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jodconverter.core.DocumentConverter;
import org.jodconverter.core.document.DefaultDocumentFormatRegistry;
import org.jodconverter.core.document.DocumentFormat;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ============================================================================
 * DOCX TEMPLATE SERVICE - Main PDF Generation Service
 * ============================================================================
 * 
 * WHAT THIS SERVICE DOES:
 * -----------------------
 * This is the main service that:
 *   1. Takes a DOCX template
 *   2. Fills in variables with JSON data (using poi-tl library)
 *   3. Converts the filled DOCX to PDF (using LibreOffice via JODConverter)
 *   4. Optionally flattens transparency for PDF 1.3 compatibility
 * 
 * THE COMPLETE FLOW:
 * ------------------
 * 
 *   DOCX Template + JSON Data
 *           ↓
 *   [poi-tl] Fill variables ({{name}}, {{address}}, etc.)
 *           ↓
 *   Rendered DOCX (with data filled in)
 *           ↓
 *   [JODConverter + LibreOffice] Convert to PDF
 *           ↓
 *   PDF (may have transparency, version 1.4+)
 *           ↓
 *   [TransparencyFlatteningService] Flatten (if enabled)
 *           ↓
 *   [PdfVersionService] Set version to 1.3
 *           ↓
 *   Final PDF 1.3 (no transparency)
 * 
 * CONFIGURATION OPTIONS (in application.properties):
 * --------------------------------------------------
 *   pdf.flatten.enabled=true        # Enable transparency flattening
 *   pdf.flatten.method=RASTERIZE    # RASTERIZE or GHOSTSCRIPT
 *   pdf.flatten.dpi=300             # DPI for rasterization (72-600)
 */
@Slf4j
@Service
public class NewDocxTemplateService {

    public static final int DEFAULT_IMAGE_W = 240;
    public static final int DEFAULT_IMAGE_H = 120;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Configure poiConfig = Configure.builder()
            .useSpringEL()   // complex/nested conditions
            .build();

    // ========================================================================
    // INJECTED DEPENDENCIES (Spring provides these automatically)
    // ========================================================================
    
    /** JODConverter's document converter (uses LibreOffice) */
    private final DocumentConverter converter;
    
    /** Custom PDF format with our export settings (from PdfFormatConfig) */
    private final DocumentFormat customPdfFormat;
    
    /** Service to change PDF version */
    private final PdfVersionService pdfVersionService;
    
    /** Service to flatten transparency by rasterization */
    private final TransparencyFlatteningService transparencyFlatteningService;

    // ========================================================================
    // CONFIGURATION (from application.properties)
    // ========================================================================
    
    /**
     * Whether to flatten transparency in the output PDF.
     * Set in application.properties: pdf.flatten.enabled=true
     */
    @Value("${pdf.flatten.enabled:true}")
    private boolean flattenEnabled;
    
    /**
     * Which method to use for flattening:
     *   - RASTERIZE: Convert each page to an image (pure Java, no external tools)
     *   - GHOSTSCRIPT: Use Ghostscript (better quality, requires gs installed)
     * Set in application.properties: pdf.flatten.method=RASTERIZE
     */
    @Value("${pdf.flatten.method:RASTERIZE}")
    private String flattenMethod;
    
    /**
     * DPI for rasterization (only used when method=RASTERIZE).
     *   72 = screen quality (fast, small files)
     *   150 = web quality
     *   300 = print quality (recommended)
     *   600 = high-quality print (slow, large files)
     * Set in application.properties: pdf.flatten.dpi=300
     */
    @Value("${pdf.flatten.dpi:300}")
    private float flattenDpi;

    // ========================================================================
    // CONSTRUCTOR (Spring calls this automatically with dependencies)
    // ========================================================================
    
    public NewDocxTemplateService(
            ObjectProvider<DocumentConverter> converterProvider,
            @Autowired(required = false) @Qualifier("pdfVersion14Format") DocumentFormat customPdfFormat,
            PdfVersionService pdfVersionService,
            TransparencyFlatteningService transparencyFlatteningService) {
        this.converter = converterProvider.getIfAvailable(); // null when office.enabled=false
        this.customPdfFormat = customPdfFormat;
        this.pdfVersionService = pdfVersionService;
        this.transparencyFlatteningService = transparencyFlatteningService;
        
        log.info("NewDocxTemplateService initialized");
        log.info("  - LibreOffice converter: {}", converter != null ? "available" : "NOT AVAILABLE");
        log.info("  - Custom PDF format: {}", customPdfFormat != null ? "configured" : "using defaults");
    }

    public RenderResult renderDocxAndPdf(byte[] templateDocx, @Nullable String json, List<byte[]> imageBytes) throws Exception {
        byte[] renderedDocx, pdf;
        if(json != null && !json.isBlank()) {
            Map<String, Object> data = parseJsonToMap(json);

            attachImagesToModel(data, imageBytes);

            // NEW: scan template for variable names & add safe defaults
            Vars vars = scanTemplateVars(templateDocx);
            applyLenientDefaults(data, vars);

            // NEW: coerce any *url* variables to hyperlinks automatically
            coerceHyperlinks(data);

            renderedDocx = renderWithPoiTl(templateDocx, data);
            pdf = convertDocxToPdf(renderedDocx);
        }
        else{
            renderedDocx = templateDocx;
            pdf = convertDocxToPdf(renderedDocx);
        }
        return new RenderResult(renderedDocx, pdf);
    }

    // ---------- lenient defaults & hyperlinks ----------

    private record Vars(Set<String> textVars, Set<String> condVars, Set<String> loopKeys) {}

    private void applyLenientDefaults(Map<String, Object> model, Vars vars) {
        // Show/hide: missing -> false
        for (String k : vars.condVars) {
            model.putIfAbsent(k, Boolean.FALSE);
        }
        // Plain text: missing -> blank
        for (String k : vars.textVars) {
            model.putIfAbsent(k, "");
        }
    }

    private void coerceHyperlinks(Map<String, Object> model) {
        // mutate in-place: any key whose name contains "url" -> link
        for (Map.Entry<String, Object> e : new ArrayList<>(model.entrySet())) {
            String key = e.getKey();
            Object val = e.getValue();
            if (key == null) continue;
            if (key.toLowerCase(Locale.ROOT).contains("url")) {
                TextRenderData link = toLinkRenderData(val);
                if (link != null) model.put(key, link);
            } else if (val instanceof Map<?,?> m) {
                // also walk nested maps/lists so inner "url" keys work
                model.put(key, deepCoerce(m));
            } else if (val instanceof List<?> l) {
                model.put(key, deepCoerce(l));
            }
        }
    }

    private Object deepCoerce(Map<?,?> m) {
        Map<String,Object> out = new LinkedHashMap<>();
        for (Map.Entry<?,?> en : m.entrySet()) {
            String k = String.valueOf(en.getKey());
            Object v = en.getValue();
            if (k.toLowerCase(Locale.ROOT).contains("url")) {
                TextRenderData link = toLinkRenderData(v);
                out.put(k, link != null ? link : v);
            } else if (v instanceof Map<?,?> mm) out.put(k, deepCoerce(mm));
            else if (v instanceof List<?> ll) out.put(k, deepCoerce(ll));
            else out.put(k, v);
        }
        return out;
    }

    private Object deepCoerce(List<?> list) {
        List<Object> out = new ArrayList<>(list.size());
        for (Object v : list) {
            if (v instanceof Map<?,?> mm) out.add(deepCoerce(mm));
            else if (v instanceof List<?> ll) out.add(deepCoerce(ll));
            else out.add(v);
        }
        return out;
    }

    private static Style defaultLinkStyle() {
        Style s = new Style();
        s.setColor("000000");          // <- black
        // s.setUnderlinePatterns(UnderlinePatterns.NONE); // uncomment to remove underline
        // s.setBold(false);              // keep links non-bold by default
        return s;
    }

    private static Style styleFromMapOrDefault(Map<?,?> m, Style def) {
        Style s = new Style();
        s.setBold(Boolean.TRUE.equals(m.get("bold")) ? true : def.isBold());
        Object underline = m.get("underline");
        if (Boolean.FALSE.equals(underline)) s.setUnderlinePatterns(UnderlinePatterns.NONE);
        Object color = m.get("color");
        if (color instanceof String hex && !hex.isBlank()) s.setColor(hex.replace("#",""));
        else s.setColor(def.getColor());
        Object size = m.get("fontSize");
        if (size instanceof Number n) s.setFontSize(n.intValue());
        return s;
    }
    private TextRenderData toLinkRenderData(Object v) {
        Style def = defaultLinkStyle();

        if (v instanceof String s) {
            String url = s.trim();
            if (url.isEmpty()) return null;
            return Texts.of(url).style(def).link(url).create();              // <- styled link
        }
        if (v instanceof Map<?,?> m) {
            Object text = m.get("text");
            Object url  = m.get("url");
            if (text instanceof String t && url instanceof String u && !u.isBlank()) {
                Style sty = styleFromMapOrDefault(m, def);
                return Texts.of(t).style(sty).link(u).create();              // <- styled link
            }
        }
        return null;
    }

    // ---------- template scanning (best-effort) ----------

    private static final Pattern TAG = Pattern.compile("\\{\\{\\s*(\\?)?\\s*(.+?)\\s*}}");
    private static final Pattern SIMPLE_IDENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern IDENT_TOKEN = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\b");

    /**
     * Extracts:
     *  - textVars: simple {{var}} occurrences
     *  - condVars: identifiers used inside {{? ...}} blocks (best-effort)
     */
    private Vars scanTemplateVars(byte[] docx) throws IOException {
        String xml = readAllWordXml(docx);
        Set<String> textVars = new HashSet<>();
        Set<String> condVars = new HashSet<>();
        Set<String> loopKeys = new HashSet<>();

        Matcher m = TAG.matcher(xml);
        while (m.find()) {
            boolean isCond = m.group(1) != null;
            String body = m.group(2);

            if (isCond) {
                if (SIMPLE_IDENT.matcher(body).matches()) {
                    loopKeys.add(body);          // {{? items}} => loop key
                    condVars.add(body);          // still track as a cond var
                } else {
                    // pull identifier-like tokens for complex expressions
                    Matcher t = IDENT_TOKEN.matcher(body);
                    while (t.find()) { String id = t.group(1); if (!isKeyword(id)) condVars.add(id); }
                }
            } else {
                if (SIMPLE_IDENT.matcher(body).matches()) textVars.add(body);
            }
        }
        return new Vars(textVars, condVars, loopKeys);
    }


    private static boolean isKeyword(String s) {
        String k = s.toLowerCase(Locale.ROOT);
        return k.equals("and") || k.equals("or") || k.equals("not")
                || k.equals("true") || k.equals("false")
                || k.equals("null") || k.equals("this") || k.equals("root");
    }

    private String readAllWordXml(byte[] docx) throws IOException {
        StringBuilder sb = new StringBuilder(64_000);
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(docx))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                String name = e.getName();
                // document + headers/footers usually hold the tags
                if (name.equals("word/document.xml") ||
                        name.startsWith("word/header") ||
                        name.startsWith("word/footer")) {
                    sb.append(new String(zin.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    // ---------- existing bits ----------

    private Map<String, Object> parseJsonToMap(String json) throws Exception {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        return mapper.readValue(json.getBytes(StandardCharsets.UTF_8),
                new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private void attachImagesToModel(Map<String, Object> model, List<byte[]> imageBytes) {
        if (imageBytes == null || imageBytes.isEmpty()) return;

        Object imagesSpec = model.get("images");
        if (imagesSpec instanceof List<?> list) {
            for (Object o : list) if (o instanceof Map<?, ?> m) {
                String key = asString(m.get("key"));
                Integer idx = asInt(m.get("index"));
                Integer w = asInt(m.get("w"));
                Integer h = asInt(m.get("h"));
                if (key != null && idx != null && idx >= 0 && idx < imageBytes.size()) {
                    int width = (w != null ? w : DEFAULT_IMAGE_W);
                    int height = (h != null ? h : DEFAULT_IMAGE_H);
                    PictureRenderData pic = Pictures.ofBytes(imageBytes.get(idx)).size(width, height).create();
                    model.put(key, pic);
                }
            }
            return;
        }
        for (int i = 0; i < imageBytes.size(); i++) {
            PictureRenderData pic = Pictures.ofBytes(imageBytes.get(i))
                    .size(DEFAULT_IMAGE_W, DEFAULT_IMAGE_H).create();
            model.put("img" + i, pic);
        }
    }

    private static String asString(Object v) { return v == null ? null : String.valueOf(v); }
    private static Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    private byte[] renderWithPoiTl(byte[] templateDocx, Map<String, Object> data) throws Exception {
        try (var in = new ByteArrayInputStream(templateDocx);
             var tpl = XWPFTemplate.compile(in, poiConfig).render(data);
             var out = new ByteArrayOutputStream()) {
            tpl.write(out);
            return out.toByteArray();
        }
    }

    /**
     * Converts DOCX to PDF with transparency flattening for PDF 1.3 compatibility.
     * 
     * THE CONVERSION PIPELINE:
     * ========================
     * 
     * STEP 1: LibreOffice Conversion
     * ------------------------------
     * JODConverter sends the DOCX to LibreOffice, which converts it to PDF.
     * At this stage, the PDF may be version 1.4-1.7 and may contain transparency.
     * 
     * STEP 2: Transparency Flattening (if enabled)
     * ---------------------------------------------
     * If pdf.flatten.enabled=true, we flatten the PDF:
     * 
     *   Method RASTERIZE:
     *   - Each page is rendered to a JPEG image (no alpha channel = no transparency)
     *   - A new PDF is created with these images as pages
     *   - Text becomes non-searchable (it's now an image)
     * 
     *   Method GHOSTSCRIPT:
     *   - Ghostscript processes the PDF with -dCompatibilityLevel=1.3
     *   - Transparency is flattened while keeping text as text
     *   - Requires Ghostscript to be installed on the system
     * 
     * STEP 3: Version Downgrade
     * -------------------------
     * The PDF version header is set to 1.3.
     * If flattening was done, this is just a formality (already 1.3 compatible).
     * If flattening was skipped, this changes the header but transparency may remain.
     * 
     * @param docxBytes The DOCX document bytes
     * @return PDF 1.3 bytes (with or without flattening based on config)
     */
    private byte[] convertDocxToPdf(byte[] docxBytes) throws Exception {
        try (var in = new ByteArrayInputStream(docxBytes);
             var out = new ByteArrayOutputStream()) {

            // ================================================================
            // STEP 1: Convert DOCX to PDF using LibreOffice
            // ================================================================
            log.info("Step 1: Converting DOCX to PDF via LibreOffice...");
            
            // Use custom PDF format if available (PDF/A-1b settings), else use default
            DocumentFormat targetFormat = (customPdfFormat != null) 
                    ? customPdfFormat 
                    : DefaultDocumentFormatRegistry.PDF;

            converter.convert(in)
                    .as(DefaultDocumentFormatRegistry.DOCX)
                    .to(out)
                    .as(targetFormat)
                    .execute();

            byte[] pdfBytes = out.toByteArray();
            log.info("  LibreOffice conversion complete. PDF size: {} bytes, version: {}", 
                    pdfBytes.length, pdfVersionService.getVersion(pdfBytes));

            // ================================================================
            // STEP 2: Flatten Transparency (if enabled)
            // ================================================================
            if (flattenEnabled) {
                log.info("Step 2: Flattening transparency (method: {})...", flattenMethod);
                
                if ("GHOSTSCRIPT".equalsIgnoreCase(flattenMethod)) {
                    // Use Ghostscript (requires gs to be installed)
                    // Best quality - keeps text as text, flattens transparency properly
                    try {
                        pdfBytes = pdfVersionService.flattenWithGhostscript(pdfBytes);
                        log.info("  Ghostscript flattening complete. New size: {} bytes", pdfBytes.length);
                    } catch (Exception e) {
                        log.warn("  Ghostscript failed ({}), falling back to rasterization", e.getMessage());
                        pdfBytes = transparencyFlatteningService.flattenTransparency(pdfBytes, flattenDpi);
                        log.info("  Rasterization fallback complete. New size: {} bytes", pdfBytes.length);
                    }
                } else {
                    // Use rasterization (pure Java, no external tools)
                    // Converts each page to an image - text becomes non-searchable
                    pdfBytes = transparencyFlatteningService.flattenTransparency(pdfBytes, flattenDpi);
                    log.info("  Rasterization complete at {} DPI. New size: {} bytes", flattenDpi, pdfBytes.length);
                }
            } else {
                log.info("Step 2: Transparency flattening DISABLED (pdf.flatten.enabled=false)");
            }

            // ================================================================
            // STEP 3: Set PDF Version to 1.3
            // ================================================================
            log.info("Step 3: Setting PDF version to 1.3...");
            pdfBytes = pdfVersionService.downgradeTo13(pdfBytes);
            log.info("  Final PDF: {} bytes, version: {}", pdfBytes.length, pdfVersionService.getVersion(pdfBytes));
            
            return pdfBytes;
        } catch (Exception e) {
            log.error("DOCX→PDF conversion failed", e);
            throw new IllegalStateException("DOCX→PDF (LibreOffice) failed: " + e.getMessage(), e);
        }
    }

    public static byte[] zipEntries(Map<String, byte[]> entries) throws java.io.IOException {
        try (var baos = new java.io.ByteArrayOutputStream();
             var zos  = new java.util.zip.ZipOutputStream(baos)) {
            for (var e : entries.entrySet()) {
                var ze = new java.util.zip.ZipEntry(e.getKey());
                zos.putNextEntry(ze);
                zos.write(e.getValue());
                zos.closeEntry();
            }
            zos.finish();
            return baos.toByteArray();
        }
    }
}