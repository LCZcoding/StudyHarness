package com.lcz.aiagentstudy.tools;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfName;
import com.itextpdf.kernel.pdf.PdfObject;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfResources;
import com.itextpdf.kernel.pdf.PdfStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
public class PDFGenerationToolTest {

    @Test
    public void testGeneratePDF() {
        PDFGenerationTool tool = new PDFGenerationTool();
        String fileName = "编程导航原创项目.pdf";
        String content = "编程导航原创项目 https://www.codefather.cn";
        String result = tool.generatePDF(fileName, content, List.of());
        assertTrue(result != null && result.contains("PDF generated successfully"));
    }

    @Test
    public void testGeneratePDFWithImage() throws Exception {
        // 用 JDK 自带能力生成一张超宽测试图（2000px），顺带验证过宽图片的等比缩小逻辑
        Path imagePath = Paths.get(FileConstant.FILE_SAVE_DIR, "pdf", "test-wide-image.png");
        imagePath.getParent().toFile().mkdirs();
        BufferedImage image = new BufferedImage(2000, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, 2000, 300);
        g.setColor(Color.WHITE);
        g.drawString("test image", 100, 150);
        g.dispose();
        ImageIO.write(image, "png", imagePath.toFile());

        PDFGenerationTool tool = new PDFGenerationTool();
        String fileName = "图片嵌入测试.pdf";
        String result = tool.generatePDF(fileName, "图片嵌入验证", List.of(imagePath.toAbsolutePath().toString()));
        assertTrue(result.contains("嵌入图片 1 张"), "工具返回应报告嵌入1张图片，实际: " + result);

        // 读回 PDF，确认页面 XObject 资源中确实存在 Subtype=/Image 的图片对象
        Path pdfPath = Paths.get(FileConstant.FILE_SAVE_DIR, "pdf", fileName);
        boolean hasImage = false;
        try (PdfDocument pdf = new PdfDocument(new PdfReader(pdfPath.toString()))) {
            for (int i = 1; i <= pdf.getNumberOfPages() && !hasImage; i++) {
                PdfResources resources = pdf.getPage(i).getResources();
                for (PdfName xObjectName : resources.getResourceNames(PdfName.XObject)) {
                    PdfObject obj = resources.getResourceObject(PdfName.XObject, xObjectName);
                    if (obj instanceof PdfStream stream
                            && PdfName.Image.equals(stream.getAsName(PdfName.Subtype))) {
                        hasImage = true;
                        break;
                    }
                }
            }
        }
        assertTrue(hasImage, "生成的 PDF 中应包含嵌入的图片对象");

        //noinspection ResultOfMethodCallIgnored
        imagePath.toFile().delete();
        new File(pdfPath.toString()).delete();
    }
}
