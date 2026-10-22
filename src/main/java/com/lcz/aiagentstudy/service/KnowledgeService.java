package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.exception.ForbiddenException;
import com.lcz.aiagentstudy.model.KnowledgeItemRow;
import com.lcz.aiagentstudy.repository.KnowledgeItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeService {
    private final KnowledgeItemRepository knowledgeItemRepository;

    // 参数名必须叫 deepSeekChatModel：项目里配了多个模型，Spring 按参数名匹配 bean
    private final ChatModel deepSeekChatModel;

    // 用 ObjectProvider：pgvector 未启用（app.pgvector.enabled=false）时容器里没有 VectorStore，
    // 直接注入会启动失败。向量是增强项，不该成为"存资料"的前置条件。
    private final ObjectProvider<VectorStore> vectorStoreProvider;

    /** 创建结果带上摘要，Controller 直接回给前端，省一次列表请求。 */
    public record CreatedItem(UUID id, String summary) {
    }

    public CreatedItem create(UUID userId, String title, String sourceUrl, String content) {
        String summary = summarizeQuietly(content);
        UUID id = knowledgeItemRepository.create(userId, title, sourceUrl, content, summary);
        indexQuietly(id, userId, title, content);
        return new CreatedItem(id, summary);
    }

    public List<KnowledgeItemRow> list(UUID userId) {
        return knowledgeItemRepository.findAllByUserId(userId);
    }

    /** 出题/复习/判分用：先验归属，再取正文。两种情况同一句文案，不泄露"存在与否"。 */
    public KnowledgeItemRow requireOwned(UUID itemId, UUID userId) {
        if (!knowledgeItemRepository.belongsTo(itemId, userId)) {
            throw new ForbiddenException("资料不存在或无权访问");
        }
        return knowledgeItemRepository.findById(itemId)
                .orElseThrow(() -> new ForbiddenException("资料不存在或无权访问"));
    }

    /**
     * 一句话摘要。失败只记日志、返回 null：资料已经存下来了，列表没有摘要也能用，
     * 不该因为模型抖动就让用户重传。
     */
    private String summarizeQuietly(String content) {
        try {
            ChatClient client = ChatClient.builder(deepSeekChatModel).build();
            String summary = client.prompt()
                    .system("用不超过 200 字概括这份学习材料的主题与覆盖范围，只输出摘要本身，不要加标题。")
                    .user(content)
                    .call()
                    .content();
            if (summary == null || summary.isBlank()) {
                return null;
            }
            String trimmed = summary.trim();
            return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300);
        } catch (RuntimeException e) {
            log.warn("生成资料摘要失败，summary 置空: cause={}", e.getMessage());
            return null;
        }
    }

    /**
     * 向量化。文档 id 直接用 knowledgeItemId —— "一条资料"与"一条向量"一一对应，
     * 将来要删/要更新，凭同一个 id 就能定位，不用再存映射。
     * 入库失败只记日志：文本已经落库，出题可以退化为直接读正文，不该因为向量库抖动就 500。
     */
    private void indexQuietly(UUID itemId, UUID userId, String title, String content) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            log.warn("VectorStore 未启用，跳过向量化: knowledgeItemId={}", itemId);
            return;
        }
        try {
            Document document = Document.builder()
                    .id(itemId.toString())
                    .text(content)
                    // 这两个 key 就是将来 filterExpression 能用的全部依据
                    .metadata("userId", userId.toString())
                    .metadata("title", title)
                    .build();
            vectorStore.add(List.of(document));
        } catch (Exception e) {
            log.warn("向量化失败，资料已保存但暂不参与 RAG: knowledgeItemId={}, cause={}",
                    itemId, e.getMessage());
        }
    }
}