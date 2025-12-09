// src/main/java/com/example/pdfgen/controller/DocxRenderController.java
package com.capability.pdfgeneration.service.controller.pdfGeneration;

import com.capability.pdfgeneration.service.models.pdfGeneration.RenderResult;
import com.capability.pdfgeneration.service.service.StorageService;
import com.capability.pdfgeneration.service.service.pdfGenerationServices.DocxTemplateValidatorService;
import com.capability.pdfgeneration.service.service.pdfGenerationServices.DocxVariableExtractorService;
import com.capability.pdfgeneration.service.service.pdfGenerationServices.NewDocxTemplateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.annotation.MultipartConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static com.capability.pdfgeneration.service.service.pdfGenerationServices.NewDocxTemplateService.zipEntries;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@MultipartConfig(maxFileSize = 10 * 1024 * 1024) // 10 MB
@ConditionalOnBean(StorageService.class)
@RestController
@RequestMapping("/docx")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:3000","https://platform.dev-capability.zinnia.com/pdfgeneration-ui"})
public class DocxRenderController {

    private final NewDocxTemplateService templateService;
    private final DocxTemplateValidatorService validatorService;
    private final StorageService storageService;
    private final DocxVariableExtractorService variableExtractor;
    private final ObjectMapper objectMapper; // inject a shared mapper

    // ---- Helpers ----

    private static void ensureValidName(String templateName) {
        if (templateName == null || templateName.isBlank()) {
            throw new IllegalArgumentException("templateName is required");
        }
        if (templateName.contains("/") || templateName.contains("\\") || templateName.contains("..")) {
            throw new IllegalArgumentException("templateName must be a file name without path separators");
        }
    }

    private static String safePdfFilename(String templateName) {
        String base = templateName.endsWith(".docx")
                ? templateName.substring(0, templateName.length() - 5)
                : templateName;
        // minimal sanitization for header usage
        return base.replaceAll("[\\r\\n\"\\\\]", "_") + ".pdf";
    }

    // ---- Endpoints ----


    /**
     * Decommisioned: use getJsonTemplate instead

     * GET /docx/variables/MyTemplate.docx
     * Downloads the template from S3 and extracts all plain and conditional variables.

    @GetMapping(value = "/variables/{templateName:.+}")
    public Map<String,String> extractVariables(@PathVariable String templateName) {
        ensureValidName(templateName);
        byte[] docx = storageService.getDocxByName(templateName);
        return variableExtractor.extractVariableNames(docx);
    }
    */

    @GetMapping(value = "/variables/{templateName:.+}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getTemplateJson(@PathVariable String templateName) {
        ensureValidName(templateName); // e.g., block ../, slashes, etc.

        String fileName = org.apache.commons.io.FilenameUtils.removeExtension(templateName) + ".json";
        String classpathLocation = "jsonTemplates/" + fileName;

        ClassPathResource resource = new ClassPathResource(classpathLocation);
        if (!resource.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Template not found: " + templateName);
        }

        try (InputStream is = resource.getInputStream()) {
            return objectMapper.readValue(is, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to parse JSON template for: " + templateName, e);
        }
    }

    @GetMapping(value = "/dropdownValues/{templateName:.+}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String,Object> getDropdownJson(@PathVariable String templateName){
        ensureValidName(templateName); // e.g., block ../, slashes, etc.

        String fileName = org.apache.commons.io.FilenameUtils.removeExtension(templateName) + ".json";
        String classpathLocation = "dropdownJson/" + fileName;

        ClassPathResource resource = new ClassPathResource(classpathLocation);
        if (!resource.exists()) {
            ClassPathResource globalResource = new ClassPathResource("dropdownJson/dropdownValues.json");
            if(!globalResource.exists()){
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Global dropdown json not found");
            }
            try (InputStream is = globalResource.getInputStream()) {
                return objectMapper.readValue(is, new TypeReference<Map<String, Object>>() {});
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Failed to parse global dropdown values JSON", e);
            }
        }

        try (InputStream is = resource.getInputStream()) {
            return objectMapper.readValue(is, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to parse dropdown values JSON for: " + templateName, e);
        }
    }

    /**
     * POST /docx/render-to-pdf?templateName=MyTemplate.docx[&debugArtifacts=true]
     * Body: multipart with "json" (String) and optional "images" (files).
     * Returns PDF by default, or a ZIP with {preconvert.docx, output.pdf, request.json} when debugArtifacts=true.
     */
    @PostMapping(
            value = "/render-to-pdf",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = { MediaType.APPLICATION_PDF_VALUE, "application/zip" }
    )
    public ResponseEntity<byte[]> renderToPdf(
            @RequestPart(value = "json") String json,
            @RequestPart(value = "images", required = false) List<MultipartFile> images,
            @RequestParam("templateName") String templateName,
            @RequestParam(value = "debugArtifacts", defaultValue = "false") boolean debugArtifacts
    ) throws Exception {

        ensureValidName(templateName);

        List<byte[]> imageBytes = new ArrayList<>();
        if (images != null) {
            for (MultipartFile mf : images) imageBytes.add(mf.getBytes());
        }

        byte[] docx = storageService.getDocxByName(templateName);
        RenderResult result = templateService.renderDocxAndPdf(docx, json, imageBytes);

        if (debugArtifacts) {
            byte[] zip = zipEntries(new LinkedHashMap<>() {{
                put("preconvert.docx", result.docx);
                put("output.pdf", result.pdf);
                put("request.json", json.getBytes(StandardCharsets.UTF_8));
            }});
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"render-debug.zip\"")
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .body(zip);
        }

        String filename = safePdfFilename(templateName);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(result.pdf);
    }

    // NEW: supports calling with no body (just query params)
    @GetMapping(
            value = "/render-to-pdf",
            produces = MediaType.APPLICATION_PDF_VALUE
    )
    public ResponseEntity<byte[]> renderToPdfNoBody(@RequestParam("templateName") String templateName) throws Exception {

        ensureValidName(templateName);

        byte[] docx = storageService.getDocxByName(templateName);
        // json=null, no images
        RenderResult result = templateService.renderDocxAndPdf(docx, null, Collections.emptyList());

        String filename = safePdfFilename(templateName);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(result.pdf);
    }

    // ---- Basic exception mapping ----

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(400).contentType(MediaType.TEXT_PLAIN).body(ex.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<String> notFound(NoSuchElementException ex) {
        return ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN).body(ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> serverError(Exception ex) {
        return ResponseEntity.status(500).contentType(MediaType.TEXT_PLAIN).body(ex.getMessage());
    }
}