package com.capability.pdfgeneration.service.service.pdfGenerationServices;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Service;

@Service
public class PdfService {
    public enum Unit { PT, MM }
    public enum Anchor { BOTTOM_LEFT, TOP_LEFT }
    public byte[] createBlankA4() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                doc.save(baos);
                return baos.toByteArray();
            }
        }
    }

    public byte[] merge(List<InputStream> sources) throws IOException {
        // 1) Materialize each InputStream to a temp file -> RandomAccessRead (low RAM)
        List<Path> temps = new ArrayList<>(sources.size());
        List<RandomAccessRead> ras = new ArrayList<>(sources.size());
        try {
            for (InputStream in : sources) {
                Path tmp = Files.createTempFile("merge-", ".pdf");
                temps.add(tmp);
                try (OutputStream os = Files.newOutputStream(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    in.transferTo(os);
                }
                ras.add(new RandomAccessReadBufferedFile(tmp.toFile()));
            }

            // 2) Configure the merger
            PDFMergerUtility merger = new PDFMergerUtility();
            merger.setDocumentMergeMode(PDFMergerUtility.DocumentMergeMode.OPTIMIZE_RESOURCES_MODE); // close sources early
            // If your inputs contain AcroForms and you want identical-named fields joined:
            // merger.setAcroFormMergeMode(PDFMergerUtility.AcroFormMergeMode.JOIN_FORM_FIELDS_MODE);

            // 3) Add sources as RandomAccessRead and stream to a byte[] destination
            for (RandomAccessRead r : ras) merger.addSource(r);
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                merger.setDestinationStream(out);

                // 4) Use a temp-file-backed stream cache + compress object streams on write
                var streamCache = org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache(); // 3.x stream cache
                var cp = new CompressParameters(); // enables compressed object streams by default
                merger.mergeDocuments(streamCache, cp);

                return out.toByteArray();
            }
        } finally {
            // ensure temp files are cleaned up
            for (RandomAccessRead r : ras) try { r.close(); } catch (IOException ignore) {}
            for (Path p : temps) try { Files.deleteIfExists(p); } catch (IOException ignore) {}
        }
    }

    /**
     * Stamps an image onto a specific page at the given position.
     * @param pdfBytes input PDF
     * @param imageBytes logo/image (PNG/JPG)
     * @param pageNumber 1-based page number
     * @param xIn X coordinate in the given units
     * @param yIn Y coordinate in the given units
     * @param wIn optional width; if null and hIn set -> keep aspect; if both null -> native size
     * @param hIn optional height; if null and wIn set -> keep aspect; if both null -> native size
     * @param units PT or MM
     * @param anchor BOTTOM_LEFT or TOP_LEFT (PDF default is bottom-left)
     * @param opacity optional 0..1 (null = fully opaque)
     */
    public byte[] stampImage(byte[] pdfBytes, byte[] imageBytes,
                             int pageNumber,
                             float xIn, float yIn,
                             Float wIn, Float hIn,
                             Unit units, Anchor anchor,
                             Float opacity) throws IOException {
        if (pageNumber < 1) throw new IllegalArgumentException("page must be >= 1");
        final float mmToPt = 72f / 25.4f;

        float x = (units == Unit.MM) ? xIn * mmToPt : xIn;
        float y = (units == Unit.MM) ? yIn * mmToPt : yIn;

        try (PDDocument doc = Loader.loadPDF(pdfBytes);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            if (pageNumber > doc.getNumberOfPages()) {
                throw new IllegalArgumentException("page out of range");
            }

            PDPage page = doc.getPage(pageNumber - 1);
            PDImageXObject img = PDImageXObject.createFromByteArray(doc, imageBytes, "stamp");

            float iw = img.getWidth(), ih = img.getHeight();
            Float w = wIn == null ? null : ((units == Unit.MM) ? wIn * mmToPt : wIn);
            Float h = hIn == null ? null : ((units == Unit.MM) ? hIn * mmToPt : hIn);

            float drawW, drawH;
            if (w == null && h == null)        { drawW = iw;             drawH = ih; }
            else if (w != null && h == null)   { drawW = w;              drawH = ih * (w / iw); }
            else if (w == null /*h!=null*/)    { drawH = h;              drawW = iw * (h / ih); }
            else                               { drawW = w;              drawH = h; }

            // Anchor & rotation
            PDRectangle box = page.getCropBox();
            float pageW = box.getWidth(), pageH = box.getHeight();
            float userX = x;
            float userY = (anchor == Anchor.TOP_LEFT) ? (pageH - y - drawH) : y;

            int rot = ((page.getRotation() % 360) + 360) % 360;
            Matrix m = new Matrix();
            switch (rot) {
                case 0 -> m.translate(userX, userY);
                case 90 -> { m.translate(pageW - userY - drawH, userX); m.rotate((float) Math.toRadians(90)); }
                case 180 -> { m.translate(pageW - userX - drawW, pageH - userY - drawH); m.rotate((float) Math.toRadians(180)); }
                case 270 -> { m.translate(userY, pageH - userX - drawW); m.rotate((float) Math.toRadians(270)); }
                default -> m.translate(userX, userY);
            }
            m.scale(drawW, drawH);

            // IMPORTANT: Append mode; resetContext=true; compress=true
            try (PDPageContentStream cs = new PDPageContentStream(
                    doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {

                if (opacity != null) {
                    PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                    gs.setNonStrokingAlphaConstant(Math.max(0f, Math.min(1f, opacity)));
                    cs.setGraphicsStateParameters(gs);
                }

                cs.drawImage(img, m);
                // (No fill/stroke/color changes; nothing to “white out”.)
            }

            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Insert the entire PDF `insert` into `base` immediately AFTER page `afterPage` (1-based).
     * Examples:
     *  - afterPage=0  -> insert at the very beginning (before page 1)
     *  - afterPage=3  -> between page 3 and 4
     *  - afterPage>=basePageCount -> append at end
     */
    public byte[] insertPdfAfterPage(byte[] base, byte[] insert, int afterPage) throws IOException {
        if (afterPage < 0) throw new IllegalArgumentException("afterPage must be >= 0 (1-based pages, 0 means insert at start)");

        try (PDDocument baseDoc = Loader.loadPDF(base);
             PDDocument insDoc  = Loader.loadPDF(insert)) {

            final int basePages = baseDoc.getNumberOfPages();

            // Normalize insertion point: 0..basePages
            // (0 = before first page, basePages = after last page)
            int insertIndex = Math.min(afterPage, basePages);

            // Split base into single-page docs
            Splitter splitter = new Splitter();
            splitter.setSplitAtPage(1); // one page per doc
            List<PDDocument> singles = splitter.split(baseDoc);

            // Assemble result: head (first insertIndex pages) + A + tail
            try (PDDocument result = new PDDocument();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {

                PDFMergerUtility mu = new PDFMergerUtility();

                // Head
                for (int i = 0; i < insertIndex; i++) {
                    try (PDDocument one = singles.get(i)) {
                        mu.appendDocument(result, one);
                    }
                }

                // Inserted document A
                mu.appendDocument(result, insDoc);

                // Tail
                for (int i = insertIndex; i < singles.size(); i++) {
                    try (PDDocument one = singles.get(i)) {
                        mu.appendDocument(result, one);
                    }
                }

                // Save with compressed object streams (smaller output)
                result.save(out, new CompressParameters());
                return out.toByteArray();
            } finally {
                // Close any leftover single-page docs not consumed in the try-with-resources above
                for (PDDocument one : singles) {
                    try { one.close(); } catch (IOException ignore) {}
                }
            }
        }
    }
}
