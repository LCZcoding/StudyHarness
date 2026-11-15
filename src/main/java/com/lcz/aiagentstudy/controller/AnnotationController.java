package com.lcz.aiagentstudy.controller;

import com.lcz.aiagentstudy.exception.BadRequestException;
import com.lcz.aiagentstudy.model.AnnotationRow;
import com.lcz.aiagentstudy.model.TargetType;
import com.lcz.aiagentstudy.service.AnnotationService;
import com.lcz.aiagentstudy.web.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 批注四接口，对应 plan.md 5.2 #4~#7。
 * 本类只做三件事：鉴权、参数形状校验、把结果裸返回；归属 / 限额 / 提示词 / 落库全在 AnnotationService。
 */
@RestController
@RequestMapping("/annotations")
@RequiredArgsConstructor
public class AnnotationController {

    private static final int MAX_TARGET_ID = 64;

    private final CurrentUserResolver currentUserResolver;
    private final AnnotationService annotationService;

    /** #4 扁平列表。前端按 blockKey 分组、按 parentId 组树。 */
    @GetMapping
    public List<AnnotationRow> list(
            @RequestParam(name = "targetType", required = false) String targetType,
            @RequestParam(name = "targetId", required = false) String targetId,
            HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        return annotationService.list(userId, parseTargetType(targetType), requireTargetId(targetId));
    }

    /** #5 生成批注。AskRequest 定义在 Service 内，@Valid 由参数绑定阶段触发。 */
    @PostMapping("/ask")
    public AnnotationRow ask(@Valid @RequestBody AnnotationService.AskRequest req,
                             HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        return annotationService.ask(userId, req);
    }

    /**
     * #6 修改 只接受 answer。用 Map 而不是 DTO，是为了让多传 question / quote 直接 400
     * （Jackson 默认忽略未知字段，DTO + @Valid 会把多余字段静默吃掉）。
     * answer 的非空 / 长度由 Service 的 updateAnswer 统一校验。
     */
    @PatchMapping("/{id}")
    public AnnotationRow patch(@PathVariable("id") long id,
                               @RequestBody Map<String, Object> body,
                               HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        if (body == null || body.size() != 1
                || !(body.get("answer") instanceof String answer)) {
            throw new BadRequestException("只允许提交 answer 字段");
        }
        return annotationService.updateAnswer(userId, id, answer);
    }

    /** #7 deletedCount 含自身，仅供前端弹提示；实际删除靠 FK 级联。 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable("id") long id, HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        return Map.of("id", id, "deletedCount", annotationService.delete(userId, id));
    }

    private static TargetType parseTargetType(String raw) {
        try {
            return TargetType.valueOf(raw);
        } catch (RuntimeException e) {
            throw new BadRequestException("targetType 不合法");
        }
    }

    private static String requireTargetId(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > MAX_TARGET_ID) {
            throw new BadRequestException("targetId 不合法");
        }
        return raw;
    }
}
