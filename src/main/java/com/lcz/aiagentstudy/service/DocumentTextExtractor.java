package com.lcz.aiagentstudy.service;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import com.lcz.aiagentstudy.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 把上传的文件转成纯文本。只管「字节 -> 文本」，不碰数据库、不碰 session。
 * 三条边界都在这里拒绝：扩展名不支持、抽不出字、太长。
 */
@Component
public class DocumentTextExtractor {

    private static final int MIN_CHARS = 100;
    private static final int MAX_CHARS = 50_000;

    public String extract(String filename, byte[] bytes) {
        String lower = (filename == null ? "" : filename).toLowerCase(Locale.ROOT);

        String text;
        if (lower.endsWith(".md") || lower.endsWith(".txt")) {
            text = new String(bytes, StandardCharsets.UTF_8);
        } else if (lower.endsWith(".pdf")) {
            text = extractPdf(bytes);
        } else {
            throw new BadRequestException("只支持 .md / .txt / .pdf");
        }

        if (text.length() < MIN_CHARS) {
            // 扫描件 PDF 抽出来是空串；这个判断同时兜住"传了个空文件"
            throw new BadRequestException("这个文件没有可提取的文字（PDF 可能是扫描件），请换成文字版或转成 .md");
        }
        if (text.length() > MAX_CHARS) {
            throw new BadRequestException("文档太长（约 " + text.length() + " 字），请拆分后分次导入");
        }
        return text;
    }

    private String extractPdf(byte[] bytes) {
        try (PdfDocument pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(bytes)))) {
            StringBuilder sb = new StringBuilder();
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                sb.append(PdfTextExtractor.getTextFromPage(pdf.getPage(page))).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            // 文件不是合法 PDF（改了扩展名）、或加了密码
            throw new BadRequestException("PDF 解析失败，请确认文件未加密且格式正常");
        }
    }
}