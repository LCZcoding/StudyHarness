//package com.lcz.aiagentstudy.rag;
//
//import jakarta.annotation.Resource;
//import org.springframework.ai.document.Document;
//import org.springframework.ai.embedding.EmbeddingModel;
//import org.springframework.ai.vectorstore.SimpleVectorStore;
//import org.springframework.ai.vectorstore.VectorStore;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//
//import java.util.List;
//
///**
// * 向量存储配置类（内存中）
// * 负责初始化向量数据库并加载 wiki 文档
// */
//@Configuration
//public class LoveAppVectorStoreConfig {
//
//    @Resource
//    private LoveAppDocumentLoader documentLoader;
//
//    /**
//     * 创建并配置向量存储 Bean
//     *
//     * @param dashscopeEmbeddingModel 阿里云百炼的 Embedding 模型
//     * @return 配置好的向量存储实例
//     */
//    @Bean
//    VectorStore loveAppVectorStore(EmbeddingModel dashscopeEmbeddingModel) {
//        // 使用 DashScope Embedding 模型构建向量存储
//        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(dashscopeEmbeddingModel).build();
//
//        // 加载 wiki 目录下的 Markdown 文档
//        List<Document> documents = documentLoader.loadMarkdowns();
//
//        // 将文档添加到向量存储中（自动进行向量化）
//        simpleVectorStore.add(documents);
//
//        return simpleVectorStore;
//    }
//}