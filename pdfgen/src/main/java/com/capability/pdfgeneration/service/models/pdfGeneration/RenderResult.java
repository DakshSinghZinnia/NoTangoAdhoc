package com.capability.pdfgeneration.service.models.pdfGeneration;

public class RenderResult {
    public final byte[] docx;
    public final byte[] pdf;
    public RenderResult(byte[] docx, byte[] pdf) {
        this.docx = docx; this.pdf = pdf;
    }
}
