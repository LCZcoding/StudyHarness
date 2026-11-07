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
     * embedding 切块参数：按 2 级及以上标题（##/###/####…）切小节，块与语义边界对齐。
     * 路径为「章标题(或文件名) · ## · ###」；# 层不进路径——长文档的章标题本就来自 #/##，
     * 短文档的 # 大标题与文件名几乎重合，再拼一层只会重复。
     * 单块离 DashScope embedding 输入上限（8192 token）很远，CHUNK_MAX 是检索粒度的权衡：
     * 太小语义不完整，太大向量被稀释、topK 覆盖的原文变少。
     */
    static final int SUBSECTION_MIN = 300;
    /** 单块硬上限：小节（或合并后）超过它就按句末标点/换行再切。 */
    static final int CHUNK_MAX = 1200;
    /** 同一小节内切出的相邻块共享的尾部字符数；不同小节之间不重叠。 */
    static final int CHUNK_OVERLAP = 150;

    private static final String SENTENCE_MARKS = "。！？；.!?;\n";

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

    /**
     * 按标题切小节：小节过碎（< SUBSECTION_MIN）与相邻小节合并成块，
     * 小节过长（> CHUNK_MAX）按句末标点/换行再切并加 overlap。
     * 未合并的块标题 = 第一个小节的完整路径；合并块 = 第一小节路径 + 其余小节最细名
     * （如「X · A + B」），标题本身就能看出合并了谁。每块 id 独立（vector_store.id 是 uuid 列）。
     */
    private List<Document> toEmbeddingChunks(UUID itemId, UUID userId, String title, String content) {
        List<List<Subsection>> groups = groupSubsections(splitSubsections(title, content));
        List<Document> chunks = new ArrayList<>();
        int seq = 0;
        for (List<Subsection> group : groups) {
            String groupTitle = groupTitle(group);
            StringBuilder text = new StringBuilder();
            for (Subsection sub : group) {
                if (text.length() > 0) {
                    text.append("\n\n");
                }
                text.append(sub.text());
            }
            for (String piece : splitLong(text.toString())) {
                chunks.add(Document.builder()
                        .id(UUID.randomUUID().toString())
                        .text(piece)
                        // userId / title / knowledgeItemId 是 filterExpression 与回溯删除的全部依据
                        .metadata("userId", userId.toString())
                        .metadata("title", groupTitle)
                        .metadata("knowledgeItemId", itemId.toString())
                        .metadata("chunkSeq", seq++)
                        .build());
            }
        }
        return chunks;
    }

    /** 未合并：第一小节完整路径。合并：「路径 + 最细名 + 最细名」，如「X · A + B」。 */
    private String groupTitle(List<Subsection> group) {
        StringBuilder sb = new StringBuilder(group.get(0).path());
        for (int i = 1; i < group.size(); i++) {
            String path = group.get(i).path();
            int last = path.lastIndexOf(" · ");
            sb.append(" + ").append(last >= 0 ? path.substring(last + 3) : path);
        }
        return sb.toString();
    }

    /** 小节：某级标题到下一个同级或更高级标题之间的内容。path 是完整路径，防"示例""小结"重名。 */
    private record Subsection(String path, String text) {
    }

    /**
     * 章节正文 -> 小节：#~###### 都是边界，层级栈维护出完整路径「# · ## · ###」。
     * 路径根优先用正文里的一级标题（比文件名更属于内容本身）；正文没有 # 时
     * 才回退到传入的章标题/文件名做前缀。标题行保留在小节正文首行（embedding 能看到）；
     * 章节里没有任何标题时整章就是一个小节。代码块里的 # 行不算标题。
     */
    private List<Subsection> splitSubsections(String sectionTitle, String content) {
        List<String> lines = List.of(content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
        boolean hasH1 = false;
        boolean inFenceScan = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                inFenceScan = !inFenceScan;
            } else if (!inFenceScan && headingLevel(trimmed) == 1) {
                hasH1 = true;
                break;
            }
        }

        List<Subsection> subs = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        List<Integer> levels = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        boolean inFence = false;

        for (String line : lines) {
            String trimmed = line.trim();
            boolean fence = trimmed.startsWith("```");
            int level = (!inFence && !fence) ? headingLevel(trimmed) : 0;
            if (level >= 1) {
                if (!body.toString().isBlank()) {
                    subs.add(new Subsection(pathOf(sectionTitle, titles, hasH1), body.toString().strip()));
                }
                body.setLength(0);
                while (!levels.isEmpty() && levels.get(levels.size() - 1) >= level) {
                    levels.remove(levels.size() - 1);
                    titles.remove(titles.size() - 1);
                }
                levels.add(level);
                titles.add(trimmed.substring(level).strip());
            }
            body.append(line).append('\n');
            if (fence) {
                inFence = !inFence;
            }
        }
        if (!body.toString().isBlank()) {
            subs.add(new Subsection(pathOf(sectionTitle, titles, hasH1), body.toString().strip()));
        }
        return subs;
    }

    /**
     * 拼小节路径。有 # 时纯用标题栈（# 已是栈底，再拼章标题会重复）；
     * 无 # 时以章标题/文件名做前缀，并与栈首做相邻去重
     * （长文档的章标题常以 " · " 拼过 ## 名，栈里又出现同一个 ## 名）。
     */
    private String pathOf(String sectionTitle, List<String> titles, boolean hasH1) {
        if (titles.isEmpty()) {
            return sectionTitle;
        }
        List<String> parts = new ArrayList<>();
        if (!hasH1) {
            for (String seg : sectionTitle.split(" · ")) {
                if (parts.isEmpty() || !parts.get(parts.size() - 1).equals(seg)) {
                    parts.add(seg);
                }
            }
        }
        for (String t : titles) {
            if (parts.isEmpty() || !parts.get(parts.size() - 1).equals(t)) {
                parts.add(t);
            }
        }
        return String.join(" · ", parts);
    }

    /** `#`~`######` 且 `#` 后紧跟空格/tab 才算标题行；返回层级，否则 0。 */
    private int headingLevel(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        if (level < 1 || level > 6 || level >= line.length()) {
            return 0;
        }
        char sep = line.charAt(level);
        return (sep == ' ' || sep == '\t') ? level : 0;
    }

    /**
     * 小节 -> 块组：相邻小节顺序累积，攒够 SUBSECTION_MIN 或再装就超 CHUNK_MAX 时出组；
     * 末尾残组放得下（合并后不超 CHUNK_MAX）就并入前一组，宁短勿碎。
     */
    private List<List<Subsection>> groupSubsections(List<Subsection> subs) {
        List<List<Subsection>> groups = new ArrayList<>();
        int len = 0;
        for (Subsection sub : subs) {
            List<Subsection> cur = groups.isEmpty() ? null : groups.get(groups.size() - 1);
            if (cur != null && (len >= SUBSECTION_MIN || len + sub.text().length() > CHUNK_MAX)) {
                len = 0;
                cur = null;
            }
            if (cur == null) {
                cur = new ArrayList<>();
                groups.add(cur);
            }
            cur.add(sub);
            len += sub.text().length();
        }
        if (groups.size() > 1) {
            List<Subsection> last = groups.get(groups.size() - 1);
            List<Subsection> prev = groups.get(groups.size() - 2);
            int lastLen = last.stream().mapToInt(s -> s.text().length()).sum();
            int prevLen = prev.stream().mapToInt(s -> s.text().length()).sum();
            if (lastLen < SUBSECTION_MIN && prevLen + lastLen <= CHUNK_MAX) {
                prev.addAll(last);
                groups.remove(groups.size() - 1);
            }
        }
        return groups;
    }

    /** 超长文本（> CHUNK_MAX）按句末标点/换行切分，相邻块带 CHUNK_OVERLAP 重叠。 */
    private List<String> splitLong(String text) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int limit = Math.min(text.length(), start + CHUNK_MAX);
            if (limit < text.length()) {
                int cut = lastSentenceEnd(text, start, limit);
                if (cut > start) {
                    limit = cut;
                }
            }
            String piece = text.substring(start, limit).strip();
            if (start > 0) {
                // overlap 起点可能落在标题行上，剥掉，避免块开头出现孤立的标题
                piece = stripLeadingHeadings(piece);
            }
            if (!piece.isBlank()) {
                pieces.add(piece);
            }
            if (limit >= text.length()) {
                break;
            }
            int next = limit - CHUNK_OVERLAP;
            if (next <= start) {
                next = start + Math.max(1, (limit - start) / 2);
            }
            start = next;
        }
        return pieces;
    }

    /** 从 end 往回找最近的句末标点（含换行），最多回退到区间中点；找不到返回 -1。 */
    private int lastSentenceEnd(String text, int start, int end) {
        int floor = start + (end - start) / 2;
        for (int i = end - 1; i >= floor; i--) {
            if (SENTENCE_MARKS.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return -1;
    }

    /** 剥掉块开头的标题行（及其前导空行）；标题属于完整小节，重叠区里出现就是噪声。 */
    private String stripLeadingHeadings(String piece) {
        String[] lines = piece.split("\n", -1);
        int i = 0;
        while (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        int first = i;
        while (i < lines.length && headingLevel(lines[i].trim()) > 0) {
            i++;
        }
        if (i == first) {
            return piece;
        }
        while (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        return i >= lines.length ? "" : String.join("\n", Arrays.copyOfRange(lines, i, lines.length));
    }
}