package com.lcz.aiagentstudy.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class LoveAppDocumentLoader {

    private final ResourcePatternResolver resourcePatternResolver;


    public LoveAppDocumentLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    /**
     * 加载 classpath:wiki/ 目录下的所有 Markdown 文件并解析为 Document 列表
     *
     * @return 解析后的文档列表
     */
    public List<Document> loadMarkdowns() {
        List<Document> documents = new ArrayList<>();
        try {
            // 获取 wiki 目录下所有 .md 文件
            Resource[] resources = resourcePatternResolver.getResources("classpath:wiki/*.md");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                // 配置 Markdown 解析器
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)  // 使用水平分割线（---）分割成多个文档
                        .withIncludeBlockquote(false)            // 不包含引用块
                        .withIncludeCodeBlock(false)             // 不包含代码块
                        .withAdditionalMetadata("filename", filename)  // 添加文件名元数据
                        .build();
                // 解析 Markdown 文件并添加到文档列表
                MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
                documents.addAll(reader.get());
            }
        } catch (IOException e) {
            throw new RuntimeException("加载 Markdown 文件失败", e);
        }
        return documents;
    }
}