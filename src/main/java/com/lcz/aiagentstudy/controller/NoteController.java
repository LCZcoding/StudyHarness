package com.lcz.aiagentstudy.controller;

import com.lcz.aiagentstudy.service.NoteService;
import com.lcz.aiagentstudy.web.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 笔记读写三接口，对应 plan.md 5.2 #2 / #3 / #8。
 *
 * <p>与 {@code AnnotationController} 同一分工：本类只做鉴权 + 触发 Bean Validation + 裸返回，
 * 归属校验与 SQL 全在 {@link NoteService}。
 */
@RestController
@RequestMapping("/notes")
@RequiredArgsConstructor
public class NoteController {

    private final CurrentUserResolver currentUserResolver;
    private final NoteService noteService;

    /**
     * #8 我的笔记列表（FR-14）。`limit` 可省，默认与上限都是 50。
     *
     * <p>`limit` 只声明成 `Integer` 不做形状校验：非法值由 Service 夹取（见 `NoteService.list`），
     * 非数字则由 Spring 的参数绑定直接 400。这里不重复写一遍校验，避免两条口径分叉。
     *
     * <p>空列表返回 `[]` 而不是 204 / 404 —— 没有笔记是正常状态，前端 `isEmpty` 就能判。
     */
    @GetMapping
    public List<NoteService.NoteListItem> list(
            @RequestParam(name = "limit", required = false) Integer limit,
            HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        return noteService.list(userId, limit);
    }

    /** #2 读笔记全文。归属失败（含不存在、非 NOTE、别人的）统一 403，见 NoteService。 */
    @GetMapping("/{messageId}")
    public NoteService.NoteView get(@PathVariable("messageId") long messageId,
                                    HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        return noteService.get(userId, messageId);
    }

    /** #3 覆盖写正文。`@Valid` 在参数绑定阶段触发，早于方法体的 requireUserId（与项目既有写法一致）。 */
    @PutMapping("/{messageId}")
    public NoteService.UpdateResult update(@PathVariable("messageId") long messageId,
                                           @Valid @RequestBody NoteService.UpdateRequest req,
                                           HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        return noteService.update(userId, messageId, req);
    }
}
