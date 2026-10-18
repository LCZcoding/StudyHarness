package com.lcz.aiagentstudy.tools;

import cn.hutool.core.io.FileUtil;
import com.itextpdf.io.image.ImageData;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.properties.HorizontalAlignment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class PDFGenerationTool {

    @Tool(description = "Generate a PDF file with text content and optional local images. "
            + "Remote image URLs must first be downloaded with the downloadResource tool; "
            + "pass the returned LOCAL file paths here, not http URLs.")
    public String generatePDF(
            @ToolParam(description = "Name of the file to save the generated PDF") String fileName,
            @ToolParam(description = "Text content to be included in the PDF") String content,
            @ToolParam(description = "LOCAL image file paths (absolute) returned by downloadResource, in display order; empty or null if no image") List<String> imagePaths) {
        String fileDir = FileConstant.FILE_SAVE_DIR + "/pdf";
        String filePath = fileDir + "/" + fileName;
        // 创建目录
        FileUtil.mkdir(fileDir);

        List<String> embedded = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        // 创建 PdfWriter 和 PdfDocument 对象
        try (PdfWriter writer = new PdfWriter(filePath);
             PdfDocument pdf = new PdfDocument(writer);
             Document document = new Document(pdf, PageSize.A4)) {
            // 自定义字体（需要人工下载字体文件到特定目录）
//                String fontPath = Paths.get("src/main/resources/static/fonts/simsun.ttf")
//                        .toAbsolutePath().toString();
//                PdfFont font = PdfFontFactory.createFont(fontPath,
//                        PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED);
            // 使用内置中文字体
            PdfFont font = PdfFontFactory.createFont("STSongStd-Light", "UniGB-UCS2-H");
            document.setFont(font);
            // 创建段落
            Paragraph paragraph = new Paragraph(content);
            // 添加段落并关闭文档
            document.add(paragraph);

            // 正文可用宽度，超过该宽度的图片等比缩小
            float availableWidth = pdf.getDefaultPageSize().getWidth()
                    - document.getLeftMargin() - document.getRightMargin();

            if (imagePaths != null) {
                for (String rawPath : imagePaths) {
                    Path imagePath = resolveImagePath(rawPath);
                    if (imagePath == null) {
                        log.warn("PDF 图片不存在，跳过: {}", rawPath);
                        skipped.add(rawPath);
                        continue;
                    }
                    try {
                        ImageData imageData = ImageDataFactory.create(imagePath.toAbsolutePath().toString());
                        Image image = new Image(imageData);
                        // 仅缩放过宽的图片，不放大原本较窄的图片
                        if (image.getImageWidth() > availableWidth) {
                            image.scaleToFit(availableWidth, Float.MAX_VALUE);
                        }
                        image.setHorizontalAlignment(HorizontalAlignment.CENTER)
                                .setMarginTop(12)
                                .setMarginBottom(6);
                        document.add(image);
                        embedded.add(imagePath.toString());
                    } catch (Exception e) {
                        // 单张图片损坏/格式不支持时跳过，不影响整份 PDF
                        log.warn("PDF 图片嵌入失败，跳过: {}，原因: {}", imagePath, e.getMessage());
                        skipped.add(rawPath);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String result = "PDF generated successfully to: " + filePath
                + "，嵌入图片 " + embedded.size() + " 张";
        if (!skipped.isEmpty()) {
            result += "，跳过 " + skipped.size() + " 张: " + skipped;
        }
        return result;
    }

    /**
     * 解析模型回传的图片路径：
     * 1. 去除模型可能带入的反引号、引号、空白等脏字符；
     * 2. 优先按绝对/相对路径查找；
     * 3. 找不到时到 downloadResource 的默认下载目录按文件名兜底。
     */
    private Path resolveImagePath(String rawPath) {
        if (rawPath == null) {
            return null;
        }
        String cleaned = rawPath.trim().replaceAll("^[`'\"]+|[`'\"]+$", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        Path path = Paths.get(cleaned);
        if (Files.isRegularFile(path)) {
            return path;
        }
        String fileName = path.getFileName().toString();
        Path inDownloadDir = Paths.get(FileConstant.FILE_SAVE_DIR, "download", fileName);
        if (Files.isRegularFile(inDownloadDir)) {
            return inDownloadDir;
        }
        return null;
    }
}
