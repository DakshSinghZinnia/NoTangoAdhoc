package com.capability.pdfgeneration.service.models.pdfGeneration;

import java.util.List;

public record PdfaReport(boolean valid, List<String> errors, PdfAProfile profile) {
}
