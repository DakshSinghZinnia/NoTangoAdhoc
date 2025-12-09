package com.capability.pdfgeneration.service.controller.pdfGeneration;

import com.capability.pdfgeneration.service.models.pdfGeneration.Report;
import com.capability.pdfgeneration.service.service.StorageService;
import com.capability.pdfgeneration.service.service.pdfGenerationServices.DocxTemplateValidatorService;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.NoSuchElementException;

@RestController
@RequestMapping("/storage")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "aws.s3.enabled", havingValue = "true", matchIfMissing = true)
@CrossOrigin(origins = {"http://localhost:3000","https://platform.dev-capability.zinnia.com/pdfgeneration-ui"})
public class StorageController {

    private final StorageService storage;
    private final DocxTemplateValidatorService validatorService;

    // -------- PDFs --------

    /** List all PDFs under PDFs/ */
    @GetMapping("/pdfs")
    public StorageService.ListResult listPdfs() { return storage.listPdfs(); }

    /** Download a specific PDF by name (under PDFs/) */
    @GetMapping(value = "/pdfs/{name:.+}")
    public ResponseEntity<byte[]> getPdf(@PathVariable String name) {
        byte[] bytes = storage.getPdfByName(name);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(bytes);
    }

    /** Upload/save a PDF under PDFs/ (multipart part name = "file") */
    @PostMapping(value = "/pdfs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StorageService.SaveResult savePdf(@RequestPart("file") MultipartFile file){
        try {
            return storage.savePdf(file.getBytes());
        } catch (Exception e) {
            throw new RuntimeException("Failed to save PDF: " + e.getMessage(), e);
        }
    }

    /** Delete a specific PDF (under PDFs/) */
    @DeleteMapping("/pdfs/{name:.+}")
    public StorageService.DeleteOneResult deletePdf(@PathVariable String name) {
        return storage.deletePdfByName(name);
    }

    /** Delete ALL PDFs (keeps folder marker) */
    @DeleteMapping("/pdfs")
    public StorageService.DeleteAllResult deleteAllPdfs() {
        return storage.deleteAllPdfs();
    }

    // -------- DOCXs --------

    /** List all DOCXs under DOCXs/ */
    @GetMapping("/docxs")
    public StorageService.ListResult listDocxs() { return storage.listDocxs(); }

    @PostMapping(value = "/docxs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StorageService.SaveResult saveDocx(@RequestPart("file") MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file uploaded");
        }
        String fileName = file.getOriginalFilename();
        byte[] bytes = file.getBytes();
        Report report = validatorService.verify(bytes);
        if (!report.ok()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Template validation failed: " + String.join("; ", report.errors));
        }
        return storage.saveDocx(bytes, fileName);
    }

    /** Download a specific DOCX by name (under DOCXs/) */
    @GetMapping(value = "/docxs/{name:.+}")
    public ResponseEntity<byte[]> getDocx(@PathVariable String name) {
        byte[] bytes = storage.getDocxByName(name);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(bytes);
    }

    /** Delete a specific DOCX (under DOCXs/) */
    @DeleteMapping("/docxs/{name:.+}")
    public StorageService.DeleteOneResult deleteDocx(@PathVariable String name) {
        return storage.deleteDocxByName(name);
    }

    /** Delete ALL DOCXs (keeps folder marker) */
    @DeleteMapping("/docxs")
    public StorageService.DeleteAllResult deleteAllDocxs() {
        return storage.deleteAllDocxs();
    }

    /** Delete ALL objects under both DOCXs/ and PDFs/ (keeps folder markers). */
    @DeleteMapping("/objects")
    public StorageService.CombinedDeleteAllResult deleteAllObjects() {
        return storage.deleteAll();
    }

    // -------- Errors --------

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<String> notFound(NoSuchElementException ex) {
        return ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN).body(ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> serverError(Exception ex) {
        return ResponseEntity.status(500).contentType(MediaType.TEXT_PLAIN).body(ex.getMessage());
    }
}