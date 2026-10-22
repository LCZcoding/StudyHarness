package com.lcz.aiagentstudy.controller;

import com.lcz.aiagentstudy.service.DocumentTextExtractor;
import com.lcz.aiagentstudy.service.KnowledgeService;
import com.lcz.aiagentstudy.web.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/knowledge-items")
@RequiredArgsConstructor
public class KnowledgeItemController {
    private static final int PREVIEW_CHARS = 200;

    private final CurrentUserResolver currentUserResolver;
    private final KnowledgeService knowledgeService;
    private final DocumentTextExtractor documentTextExtractor;

    public record CreateRequest(
            @NotBlank(message = "title 不能为空")
            @Size(max = 100, message = "title 过长") String title,
            @NotBlank(message = "content 不能为空")
            @Size(max = 20000, message = "正文最多 20000 字") String content) {
    }

    public record CreateResponse(String knowledgeItemId, String preview, String summary) {
    }

    public record ItemView(UUID knowledgeItemId, String title, String sourceUrl,
                           String summary, OffsetDateTime createdAt) {
    }
    // 粘贴文本保存为一份资料
    @PostMapping
    public ResponseEntity<CreateResponse> create(@Valid @RequestBody CreateRequest req,
                                                 HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        KnowledgeService.CreatedItem created = knowledgeService.create(userId, req.title(), null, req.content());
        return ResponseEntity.created(URI.create("/knowledge-items/" + created.id()))
                .body(new CreateResponse(created.id().toString(), preview(req.content()), created.summary()));
    }
    // 上传`.md` /`.txt` /`.pdf` 保存为资料
    @PostMapping("/upload")
    public ResponseEntity<CreateResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            HttpServletRequest request) throws IOException {

        UUID userId = currentUserResolver.requireUserId(request);

        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        // 扩展名、空文本、超长三处拒绝都在这里抛 BadRequestException（-> 400）
        String content = documentTextExtractor.extract(filename, file.getBytes());

        // 标题没填就用文件名（去扩展名）；空文件名兜底成"未命名资料"
        String finalTitle = (title == null || title.isBlank()) ? defaultTitle(filename) : title;

        KnowledgeService.CreatedItem created = knowledgeService.create(userId, finalTitle, filename, content);
        return ResponseEntity.created(URI.create("/knowledge-items/" + created.id()))
                .body(new CreateResponse(created.id().toString(), preview(content), created.summary()));
    }
    // 列出当前用户的全部资料，供资料页渲染
    @GetMapping
    public List<ItemView> list(HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        return knowledgeService.list(userId).stream()
                .map(item -> new ItemView(item.id(), item.title(), item.sourceUrl(),
                        item.summary(), item.createdAt()))
                .toList();
    }

    private static String defaultTitle(String filename) {
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String base = slash >= 0 ? filename.substring(slash + 1) : filename;
        int dot = base.lastIndexOf('.');
        base = dot > 0 ? base.substring(0, dot) : base;
        return base.isBlank() ? "未命名资料" : base.substring(0, Math.min(100, base.length()));
    }

    /** 前 200 字，给前端确认"抽出来的文字是不是乱的"。换行压成空格，避免预览框里空行太多。 */
    private static String preview(String content) {
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS) + "…";
    }
}