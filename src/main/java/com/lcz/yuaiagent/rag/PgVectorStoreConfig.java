package com.lcz.yuaiagent.rag;

import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

@Configuration
@ConditionalOnProperty(name = "spring.datasource.url")
public class PgVectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStoreConfig.class);

    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;


    @Bean
    @ConditionalOnProperty (name = "app.pgvector.enabled",havingValue = "true") //当app.pgvector.enabled是true时，执行入库。避免重复入库
    public VectorStore pgVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel dashscopeEmbeddingModel) {
        VectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                .dimensions(1024)                    // 不要盲目设置
                .distanceType(COSINE_DISTANCE)       // Optional: defaults to COSINE_DISTANCE
                .indexType(HNSW)                     // Optional: defaults to HNSW
                .initializeSchema(true)              // Optional: defaults to false
                .schemaName("public")                // Optional: defaults to "public"
                .vectorTableName("vector_store")     // Optional: defaults to "vector_store"
                .maxDocumentBatchSize(10000)         // Optional: defaults to 10000
                .build();
        //加载wiki文档
        List<Document> documents = loveAppDocumentLoader.loadMarkdowns();

        // 分批添加
        int batchSize = 10;
        for(int i = 0; i < documents.size(); i += batchSize){
            //添加文档到pg存储
            vectorStore.add(documents.subList(i, Math.min(i + batchSize, documents.size())));
        }
        return vectorStore;
    }
}