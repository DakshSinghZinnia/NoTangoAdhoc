package com.capability.pdfgeneration.service.service.pdfGenerationServices;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.PDFAIdentificationSchema;
import org.apache.xmpbox.xml.DomXmpParser;
import org.apache.pdfbox.preflight.ValidationResult;
import org.apache.pdfbox.preflight.parser.PreflightParser;
import org.springframework.stereotype.Service;

import com.capability.pdfgeneration.service.models.pdfGeneration.PdfAProfile;
import com.capability.pdfgeneration.service.models.pdfGeneration.PdfaReport;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
public class PdfValidationService {

    public PdfaReport validatePdfa(byte[] pdfBytes) {
        Path tmp = null;
        try {
            // Validate (PDFBox 3.x)
            tmp = Files.createTempFile("preflight-", ".pdf");
            Files.write(tmp, pdfBytes);

            ValidationResult vr = PreflightParser.validate(tmp.toFile()); // or .validate(tmp.toFile())

            // Extract claimed PDF/A profile from XMP (if present)
            PdfAProfile profile = readPdfAProfile(pdfBytes);

            if (vr.isValid()) {
                return new PdfaReport(true, List.of(), profile);
            } else {
                var msgs = vr.getErrorsList().stream()
                        .map(e -> e.getErrorCode() + " - " + e.getDetails())
                        .toList();
                return new PdfaReport(false, msgs, profile);
            }

        } catch (Exception e) {
            return new PdfaReport(false, List.of("Validator error: " + e.getMessage()), null);
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (Exception ignore) {}
        }
    }

    /** Reads pdfaid:part and pdfaid:conformance from XMP (returns null if absent). */
    private PdfAProfile readPdfAProfile(byte[] pdfBytes) {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDMetadata md = doc.getDocumentCatalog().getMetadata();
            if (md == null) return null;

            try (InputStream is = md.createInputStream()) {
                DomXmpParser parser = new DomXmpParser();
                XMPMetadata xmp = parser.parse(is);
                PDFAIdentificationSchema id = xmp.getPDFAIdentificationSchema();
                if (id == null) return null;

                Integer part = id.getPart();            // e.g., 1, 2, or 3
                String conf = id.getConformance();      // e.g., "B", "U", "A"
                if (part == null && conf == null) return null;

                return new PdfAProfile(
                        part == null ? null : String.valueOf(part),
                        conf
                );
            }
        } catch (Exception ignore) {
            return null; // Silently ignore malformed/missing XMP; validation result still returned
        }
    }
}