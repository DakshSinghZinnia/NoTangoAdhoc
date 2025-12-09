package com.capability.pdfgeneration.service.controller.pdfGeneration;

import com.capability.pdfgeneration.service.models.pdfGeneration.PdfaReport;
import com.capability.pdfgeneration.service.service.pdfGenerationServices.PdfService;
import com.capability.pdfgeneration.service.service.pdfGenerationServices.PdfValidationService;

import jakarta.servlet.annotation.MultipartConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@MultipartConfig(maxFileSize = 10 * 1024 * 1024) // 10 MB
@RestController
@RequestMapping("/pdf")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:3000","https://platform.dev-capability.zinnia.com/pdfgeneration-ui"})
public class PdfController  {
    private final PdfService pdfService;
    private final PdfValidationService pdfValidationService;

    @GetMapping("/blank")
    public ResponseEntity<byte[]> blank() throws IOException {
        byte[] pdf = pdfService.createBlankA4();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=blank.pdf")
                .body(pdf);
    }

    @PostMapping(value="/merge", consumes= MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> merge(@RequestPart("files") List<MultipartFile> files) throws IOException {
        var inputs = new ArrayList<InputStream>();
        for (var f : files) inputs.add(f.getInputStream());
        byte[] merged = pdfService.merge(inputs);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=merged.pdf")
                .body(merged);
    }

    @PostMapping(value = "/stamp-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "application/pdf")
    public ResponseEntity<byte[]> stampImage(
            @RequestPart("pdf") MultipartFile pdf,
            @RequestPart("image") MultipartFile image,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam float x,
            @RequestParam float y,
            @RequestParam(required = false) Float width,
            @RequestParam(required = false) Float height,
            @RequestParam(defaultValue = "pt") String units,        // "pt" or "mm"
            @RequestParam(defaultValue = "bottom-left") String anchor, // "bottom-left" or "top-left"
            @RequestParam(required = false) Float opacity
    ) throws Exception {

        var unitEnum = "mm".equalsIgnoreCase(units) ? PdfService.Unit.MM : PdfService.Unit.PT;
        var anchorEnum = "top-left".equalsIgnoreCase(anchor)
                ? PdfService.Anchor.TOP_LEFT
                : PdfService.Anchor.BOTTOM_LEFT;

        byte[] out = pdfService.stampImage(
                pdf.getBytes(),
                image.getBytes(),
                page,
                x, y,
                width, height,
                unitEnum, anchorEnum,
                opacity
        );

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=stamped.pdf")
                .body(out);
    }

    //Validation against the PDF/A - 1 standard.
    @PostMapping(value="/validate/pdfa", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PdfaReport> validatePdfa(@RequestPart("file") MultipartFile file) throws IOException {
        return ResponseEntity.ok(pdfValidationService.validatePdfa(file.getBytes()));
    }

    /**
     * Insert whole PDF `a` into `b` immediately after page `x`.
     * curl -F "b=@base.pdf" -F "a=@insert.pdf" "http://localhost:8080/pdf/insert?x=3" -o out.pdf
     * if x=0, inserts at start
     * if x>=number of pages, inserts at end
     */
    @PostMapping(value = "/insert", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "application/pdf")
    public ResponseEntity<byte[]> insert(
            @RequestPart("basePdf") MultipartFile base,
            @RequestPart("insertPdf") MultipartFile insert,
            @RequestParam(name = "pageNumber") int afterPage // 1-based; 0 inserts at start
    ) throws Exception {
        byte[] out = pdfService.insertPdfAfterPage(base.getBytes(), insert.getBytes(), afterPage);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=inserted.pdf")
                .body(out);
    }

}
