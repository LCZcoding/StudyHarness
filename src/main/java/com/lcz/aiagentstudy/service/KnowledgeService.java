package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.exception.BadRequestException;
import com.lcz.aiagentstudy.exception.ForbiddenException;
import com.lcz.aiagentstudy.model.KnowledgeItemRow;
import com.lcz.aiagentstudy.repository.KnowledgeItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.*;

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

    private final DocumentSplitter documentSplitter;
    private final AiCallRecorder aiCallRecorder;

    /** 创建结果带上摘要，Controller 直接回给前端，省一次列表请求。 */
    public record CreatedItem(UUID id, String summary) {
    }

//    public CreatedItem create(UUID userId, String title, String sourceUrl, String content) {
//        String summary = summarizeQuietly(content);
//        UUID id = knowledgeItemRepository.create(userId, title, sourceUrl, content, summary);/
//        indexQuietly(id, userId, title, content);
//        return new CreatedItem(id, summary);
//    }

    public record CreatedSection(UUID id, int sectionNo, String title, int chars, String summary) {
    }

    public record CreatedDocument(String docTitle, List<CreatedSection> sections) {
    }

    /**
     * 粘贴与上传共用：一份文档 -> N 个章节。
     * 同名文档抛 400；切分超过 30 章也抛 400；某章摘要失败只把该章 summary 置空，不阻断落库。
     */
    public CreatedDocument createDocument(UUID userId, String docTitle, String sourceUrl, String content) {
        if (knowledgeItemRepository.existsDocTitle(userId, docTitle)) {
            throw new BadRequestException("已存在同名文档，请改名后重新上传");
        }

        List<DocumentSplitter.Section> sections = documentSplitter.split(docTitle, content);
        List<CreatedSection> created = new ArrayList<>(sections.size());

        for (int i = 0; i < sections.size(); i++) {
            DocumentSplitter.Section section = sections.get(i);
            int sectionNo = i + 1;
            // 每章一次模型调用。失败只让这一章没有摘要，不影响其他章节
            String summary = summarizeQuietly(userId, section.content());
            UUID id = knowledgeItemRepository.createSection(
                    userId, docTitle, sectionNo, section.title(), sourceUrl, section.content(), summary);
            indexQuietly(id, userId, section.title(), section.content());
            created.add(new CreatedSection(id, sectionNo, section.title(), section.content().length(), summary));
        }
        return new CreatedDocument(docTitle, created);
    }

//    public List<KnowledgeItemRow> list(UUID userId) {
//        return knowledgeItemRepository.findAllByUserId(userId);
//    }

    public record SectionView(UUID id, int sectionNo, String title, int chars,
                              String summary, String status) {
    }

    public record DocumentGroup(String docTitle, int sectionCount, int passedCount,
                                List<SectionView> sections) {
    }

    /**
     * 资料页：按文档分组只在这里做一次。进度页与交错复习复用同一套分组口径，
     * 前端不再按 docTitle 二次分组。
     */
    public List<DocumentGroup> listDocuments(UUID userId) {
        Map<String, List<KnowledgeItemRepository.SectionRow>> grouped = new LinkedHashMap<>();
        for (KnowledgeItemRepository.SectionRow row : knowledgeItemRepository.findAllByUserId(userId)) {
            grouped.computeIfAbsent(row.docTitle(), key -> new ArrayList<>()).add(row);
        }

        List<DocumentGroup> result = new ArrayList<>(grouped.size());
        grouped.forEach((docTitle, rows) -> {
            List<SectionView> sections = rows.stream()
                    .map(row -> new SectionView(row.id(), row.sectionNo(), row.title(),
                            row.chars(), row.summary(), statusOf(row)))
                    .toList();
            int passed = (int) sections.stream().filter(s -> "PASSED".equals(s.status())).count();
            result.add(new DocumentGroup(docTitle, sections.size(), passed, sections));
        });
        return result;
    }

    /** 三态判定只写这一处：未开始 / 进行中 / 已通过。规则见 product-spec.md 10.5。 */
    private String statusOf(KnowledgeItemRepository.SectionRow row) {
        if (row.quizCount() == 0) {
            return "NOT_STARTED";
        }
        return row.answeredCount() == row.quizCount() && row.allPassed() ? "PASSED" : "IN_PROGRESS";
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
    private String summarizeQuietly(UUID userId, String content) {
        long startedAt = aiCallRecorder.start();
        try {
            ChatClient client = ChatClient.builder(deepSeekChatModel).build();
            ChatResponse response = client.prompt()
                    .system("用不超过 200 字概括这份学习材料的主题与覆盖范围，只输出摘要本身，不要加标题。")
                    .user(content)
                    .call()
                    .chatResponse();
            aiCallRecorder.recordCall(userId, "SUMMARY", startedAt, response);

            String summary = response == null || response.getResult() == null
                    ? null : response.getResult().getOutput().getText();
            if (summary == null || summary.isBlank()) {
                return null;
            }
            String trimmed = summary.trim();
            return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300);
        } catch (RuntimeException e) {
            aiCallRecorder.recordFailure(userId, "SUMMARY", startedAt, e);
            log.warn("生成资料摘要失败，summary 置空: cause={}", e.getMessage());
            return null;
        }
    }

    /**
     * 单条向量的最大字符数。章节 8000~12000 字直接送 embedding 会触发
     * "Tokens in a single document exceeds the maximum number of allowed input tokens"
     * （DashScope embedding 单条输入上限 8192 token，中文约 1 字 1 token）。
     * 章节切分服务于展示与出题，embedding 切块服务于召回，两者粒度本来就不一样。
     */
    private static final int EMBED_CHUNK_CHARS = 1000;

    /** DashScope embedding 单次请求的文本条数上限。 */
    private static final int EMBED_BATCH_SIZE = 10;

    /**
     * 向量化。一章可能切成多条向量，每条向量自带 knowledgeItemId，
     * 将来要删/要更新，凭这个 key 就能定位该章的全部向量。
     * 入库失败只记日志：文本已经落库，出题可以退化为直接读正文，不该因为向量库抖动就 500。
     */
    private void indexQuietly(UUID itemId, UUID userId, String title, String content) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            log.warn("VectorStore 未启用，跳过向量化: knowledgeItemId={}", itemId);
            return;
        }
        try {
            List<Document> chunks = toEmbeddingChunks(itemId, userId, title, content);
            // DashScope embedding 单次请求最多 10 条文本，按 10 条一批提交
            for (int i = 0; i < chunks.size(); i += EMBED_BATCH_SIZE) {
                vectorStore.add(chunks.subList(i, Math.min(chunks.size(), i + EMBED_BATCH_SIZE)));
            }
        } catch (Exception e) {
            log.warn("向量化失败，资料已保存但暂不参与 RAG: knowledgeItemId={}, cause={}",
                    itemId, e.getMessage());
        }
    }

    /** 按 1000 字切块，尽量在换行/空格处断开；每块 id 独立（vector_store.id 是 uuid 列）。 */
    private List<Document> toEmbeddingChunks(UUID itemId, UUID userId, String title, String content) {
        List<Document> chunks = new ArrayList<>();
        int length = content.length();
        int start = 0;
        int seq = 0;
        while (start < length) {
            int end = Math.min(length, start + EMBED_CHUNK_CHARS);
            if (end < length) {
                int cut = Math.max(content.lastIndexOf('\n', end - 1), content.lastIndexOf(' ', end - 1));
                if (cut > start) {
                    end = cut + 1;
                }
            }
            String text = content.substring(start, end);
            if (!text.isBlank()) {
                chunks.add(Document.builder()
                        .id(UUID.randomUUID().toString())
                        .text(text)
                        // userId / title / knowledgeItemId 是 filterExpression 与回溯删除的全部依据
                        .metadata("userId", userId.toString())
                        .metadata("title", title)
                        .metadata("knowledgeItemId", itemId.toString())
                        .metadata("chunkSeq", seq)
                        .build());
            }
            start = end;
            seq++;
        }
        return chunks;
    }
}