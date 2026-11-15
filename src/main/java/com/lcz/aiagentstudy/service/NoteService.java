package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.exception.BadRequestException;
import com.lcz.aiagentstudy.exception.ForbiddenException;
import com.lcz.aiagentstudy.repository.MessageRepository;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 笔记读写（plan.md 5.2 #2 / #3 / #8，FR-04 / FR-05 / FR-12 / FR-14）。
 *
 * <p>只做两件事：归属校验 + 组装响应。SQL 全在 T004 交付的 {@link MessageRepository} 里，
 * 本类不注入 {@code JdbcTemplate} —— 与 {@code AnnotationService} 同一条纪律，
 * 否则「NOTE 归属」的 JOIN 会出现两份，其中一份迟早漏掉 {@code type='NOTE'}。
 *
 * <p>**不标 {@code @Transactional}**：三个方法各只有一条 SQL（读一条、改一条、列表一条），
 * 归属校验与改动之间不存在需要一起回滚的中间状态；包上事务只会多占一次连接。
 */
@Service
@RequiredArgsConstructor
public class NoteService {

    /**
     * 正文上限（plan.md 5.2 #3）。20000 字远大于提示词要求的 1000~2000 字，
     * 留给用户贴代码/表格的余量；设上限是为了挡住一次几 MB 的误粘贴。
     */
    private static final int MAX_CONTENT = 20000;

    /** 列表默认 / 上限条数（plan.md 5.2 #8）。两者同值：首屏 50 条够用，不做游标分页。 */
    private static final int LIST_LIMIT = 50;

    private final MessageRepository messageRepository;

    /** #2 响应。字段顺序与 plan 一致，`type` 恒为 "NOTE"，前端可据此断言"这确实是一条笔记"。 */
    public record NoteView(
            long messageId, UUID conversationId, String type, String content,
            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /**
     * #8 列表项。字段对齐 plan.md 5.2 #8，**刻意不带 {@code type}**：能进这个列表的必然是 NOTE，
     * 恒等字段只会让人以为它可能变。也**不带标题 / 摘要 / 字数**，由前端从 {@code content} 自己算。
     */
    public record NoteListItem(
            long messageId, UUID conversationId, String content,
            int annotationCount, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /** #3 请求体。只接受 content；多传字段由 Jackson 忽略（本接口不要求严格拒收）。 */
    public record UpdateRequest(@NotNull(message = "content 不能为空")
                                @Size(max = MAX_CONTENT, message = "笔记正文最多 20000 字")
                                String content) {
    }

    /** #3 响应。只回 messageId + 新的 updatedAt，正文让前端用自己刚提交的那份，省一次回读。 */
    public record UpdateResult(long messageId, OffsetDateTime updatedAt) {
    }

    /** #2 读笔记全文。 */
    public NoteView get(UUID userId, long messageId) {
        requireOwnNote(userId, messageId);
        // 归属已通过还查不到，只可能是两次查询之间被删了：按 400 兜底，不与 403 抢语义。
        MessageRepository.NoteMessage note = messageRepository.findNoteById(messageId)
                .orElseThrow(() -> new BadRequestException("该消息不是笔记"));
        return new NoteView(note.messageId(), note.conversationId(), "NOTE",
                note.content(), note.createdAt(), note.updatedAt());
    }

    /**
     * #3 覆盖写正文，同时写 {@code updated_at}。
     *
     * <p>**不重算后端锚点**：批注的 {@code blockKey} / {@code quote} 存在 t_annotation 里，
     * 正文改动后由前端加载时按新正文重新定位（plan.md 7.2）；后端此刻重算一遍既没有正文分块能力，
     * 也会和前端算出两套结果。
     *
     * <p>允许提交空串：覆盖写就是覆盖写，用户把整篇删空再保存是他的权利。
     * 只挡 null 与超长，不替用户猜"空正文是不是误操作"。
     */
    public UpdateResult update(UUID userId, long messageId, UpdateRequest req) {
        requireOwnNote(userId, messageId);
        // updateContent 自带 where type='NOTE'；返回 empty 说明那一行在两步之间被删了。
        OffsetDateTime updatedAt = messageRepository.updateContent(messageId, req.content())
                .orElseThrow(() -> new BadRequestException("该消息不是笔记"));
        return new UpdateResult(messageId, updatedAt);
    }

    /**
     * #8 我的笔记列表（FR-14）。归属直接写进 SQL 的 {@code c.user_id = ?}：
     * 列表是"本人的全部笔记"，没有单条 id 可校验，也就不存在 403 分支 —— 别人的笔记根本查不出来。
     *
     * <p>{@code limit} 取**夹取**而非报错：这不是需要用户纠错的输入错误，越界就按边界值处理，
     * 少一个错误码分支。{@code null}（没传）与超上限都落到 50，与 plan「默认和上限都是 50」一致；
     * 负数 / 0 夹到 1，避免 PG 的 {@code LIMIT -1} 直接抛异常。
     */
    public List<NoteListItem> list(UUID userId, Integer limit) {
        int effective = limit == null || limit > LIST_LIMIT ? LIST_LIMIT : Math.max(limit, 1);
        return messageRepository.findNotesByUser(userId, effective).stream()
                .map(row -> new NoteListItem(row.messageId(), row.conversationId(), row.content(),
                        row.annotationCount(), row.createdAt(), row.updatedAt()))
                .toList();
    }

    /**
     * 归属校验（FR-12）。**读和改共用同一个方法**，保证两条路径的拒绝口径逐字相同 ——
     * 分开写就会出现"读返回 403、改返回 404"，等于用两条错误的差异泄露「这条 id 到底存不存在」。
     *
     * <p>三件事在 SQL 里一次判完（plan.md 4.4）：行存在、{@code type='NOTE'}、会话属于本人。
     * 所以「把自己会话里的普通聊天消息当笔记 id」也会落在这里 → 403，不会漏到 400 分支。
     */
    private void requireOwnNote(UUID userId, long messageId) {
        if (!messageRepository.noteBelongsToUser(messageId, userId)) {
            throw new ForbiddenException("笔记不存在或无权访问");
        }
    }
}
