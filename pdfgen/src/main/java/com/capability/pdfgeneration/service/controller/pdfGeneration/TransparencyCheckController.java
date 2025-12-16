package com.capability.pdfgeneration.service.controller.pdfGeneration;

import com.capability.pdfgeneration.service.service.pdfGenerationServices.TransparencyDetectionService;
import com.capability.pdfgeneration.service.service.pdfGenerationServices.TransparencyDetectionService.TransparencyReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * ============================================================================
 * TRANSPARENCY CHECK CONTROLLER
 * ============================================================================
 * 
 * REST endpoint to check if a PDF has transparency.
 * 
 * USAGE:
 * ------
 * 
 * 1. Using curl:
 *    curl -X POST -F "file=@output.pdf" http://localhost:8080/pdf/check-transparency
 * 
 * 2. Using a web browser:
 *    Open http://localhost:8080/pdf/check-transparency/form
 *    Upload your PDF file
 * 
 * RESPONSE:
 * ---------
 * {
 *   "hasTransparency": false,
 *   "pdfVersion": 1.3,
 *   "pageCount": 5,
 *   "issues": [],
 *   "summary": "... human readable report ..."
 * }
 */
@Slf4j
@RestController
@RequestMapping("/pdf")
@RequiredArgsConstructor
public class TransparencyCheckController {

    private final TransparencyDetectionService transparencyService;

    /**
     * Check a PDF for transparency.
     * 
     * Example:
     *   curl -X POST -F "file=@output.pdf" http://localhost:8080/pdf/check-transparency
     */
    @PostMapping(value = "/check-transparency", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> checkTransparency(
            @RequestParam("file") MultipartFile file) throws IOException {
        
        log.info("Checking transparency for file: {}", file.getOriginalFilename());
        
        byte[] pdfBytes = file.getBytes();
        TransparencyReport report = transparencyService.detectTransparency(pdfBytes);
        
        Map<String, Object> response = new HashMap<>();
        response.put("fileName", file.getOriginalFilename());
        response.put("hasTransparency", report.hasTransparency());
        response.put("pdfVersion", report.pdfVersion());
        response.put("pageCount", report.pageCount());
        response.put("issues", report.issues());
        response.put("summary", report.getSummary());
        
        // Also print to console for easy viewing
        System.out.println("\n" + report.getSummary() + "\n");
        
        return ResponseEntity.ok(response);
    }

    /**
     * Simple HTML form for browser-based testing.
     * 
     * Open in browser: http://localhost:8080/pdf/check-transparency/form
     */
    @GetMapping(value = "/check-transparency/form", produces = MediaType.TEXT_HTML_VALUE)
    public String getUploadForm() {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>PDF Transparency Checker</title>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        max-width: 800px;
                        margin: 50px auto;
                        padding: 20px;
                        background: #f5f5f5;
                    }
                    .container {
                        background: white;
                        padding: 30px;
                        border-radius: 10px;
                        box-shadow: 0 2px 10px rgba(0,0,0,0.1);
                    }
                    h1 { color: #333; margin-bottom: 10px; }
                    .subtitle { color: #666; margin-bottom: 30px; }
                    form {
                        display: flex;
                        flex-direction: column;
                        gap: 20px;
                    }
                    input[type="file"] {
                        padding: 15px;
                        border: 2px dashed #ccc;
                        border-radius: 8px;
                        background: #fafafa;
                        cursor: pointer;
                    }
                    input[type="file"]:hover { border-color: #007bff; }
                    button {
                        padding: 15px 30px;
                        background: #007bff;
                        color: white;
                        border: none;
                        border-radius: 8px;
                        font-size: 16px;
                        cursor: pointer;
                    }
                    button:hover { background: #0056b3; }
                    #result {
                        margin-top: 20px;
                        padding: 20px;
                        background: #f8f9fa;
                        border-radius: 8px;
                        white-space: pre-wrap;
                        font-family: monospace;
                        display: none;
                    }
                    .success { border-left: 4px solid #28a745; }
                    .warning { border-left: 4px solid #ffc107; }
                    .error { border-left: 4px solid #dc3545; }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>🔍 PDF Transparency Checker</h1>
                    <p class="subtitle">Upload a PDF to check if it contains transparency (PDF 1.3 compatibility check)</p>
                    
                    <form id="uploadForm" enctype="multipart/form-data">
                        <input type="file" name="file" accept=".pdf" required>
                        <button type="submit">Check for Transparency</button>
                    </form>
                    
                    <div id="result"></div>
                </div>
                
                <script>
                    document.getElementById('uploadForm').onsubmit = async (e) => {
                        e.preventDefault();
                        const formData = new FormData(e.target);
                        const resultDiv = document.getElementById('result');
                        
                        resultDiv.style.display = 'block';
                        resultDiv.className = '';
                        resultDiv.textContent = 'Checking...';
                        
                        try {
                            const response = await fetch('/pdf/check-transparency', {
                                method: 'POST',
                                body: formData
                            });
                            const data = await response.json();
                            
                            resultDiv.textContent = data.summary;
                            resultDiv.className = data.hasTransparency ? 'warning' : 'success';
                        } catch (err) {
                            resultDiv.textContent = 'Error: ' + err.message;
                            resultDiv.className = 'error';
                        }
                    };
                </script>
            </body>
            </html>
            """;
    }
}

