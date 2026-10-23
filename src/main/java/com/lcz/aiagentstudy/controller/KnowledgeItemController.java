package com.lcz.aiagentstudy.controller;

import com.lcz.aiagentstudy.model.KnowledgeItemRow;
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
            @Size(max = 100, message = "文档名过长") String title,
            @NotBlank(message = "content 不能为空")
            @Size(max = 200000, message = "正文最多 20 万字") String content) {
    }

    public record SectionView(UUID knowledgeItemId, int sectionNo, String title, int chars, String summary) {
    }

    public record CreateResponse(String docTitle, List<SectionView> sections) {
    }

    public record SectionItem(UUID knowledgeItemId, int sectionNo, String title, int chars,
                              String summary, String status) {
    }

    public record DocumentView(String docTitle, int sectionCount, int passedCount, List<SectionItem> sections) {
    }

    public record SectionDetail(UUID knowledgeItemId, String docTitle, int sectionNo, String title,
                                int chars, String content) {
    }
    // 粘贴文本保存为一份资料
    @PostMapping
    public ResponseEntity<CreateResponse> create(@Valid @RequestBody CreateRequest req,
                                                 HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        KnowledgeService.CreatedDocument created =
                knowledgeService.createDocument(userId, req.title(), null, req.content());
        return ResponseEntity.created(URI.create("/knowledge-items/" + created.sections().get(0).id()))
                .body(toResponse(created));
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

        KnowledgeService.CreatedDocument created = knowledgeService.createDocument(userId, finalTitle, filename, content);
        return ResponseEntity.created(URI.create("/knowledge-items/" + created.sections().get(0).id()))
                .body(toResponse(created));
    }
    // 列出当前用户的全部资料，供资料页渲染
    @GetMapping
    public List<DocumentView> list(HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        return knowledgeService.listDocuments(userId).stream()
                .map(doc -> new DocumentView(doc.docTitle(), doc.sectionCount(), doc.passedCount(),
                        doc.sections().stream()
                                .map(s -> new SectionItem(s.id(), s.sectionNo(), s.title(),
                                        s.chars(), s.summary(), s.status()))
                                .toList()))
                .toList();
    }

    /** 章节正文。阅读页与"直接检测"之前都走这里。 */
    @GetMapping("/{knowledgeItemId}")
    public SectionDetail detail(@PathVariable UUID knowledgeItemId, HttpServletRequest request) {
        UUID userId = currentUserResolver.requireUserId(request);
        KnowledgeItemRow row = knowledgeService.requireOwned(knowledgeItemId, userId);
        return new SectionDetail(row.id(), row.docTitle(), row.sectionNo(), row.title(),
                row.content().length(), row.content());
    }

    private static CreateResponse toResponse(KnowledgeService.CreatedDocument created) {
        return new CreateResponse(created.docTitle(), created.sections().stream()
                .map(s -> new SectionView(s.id(), s.sectionNo(), s.title(), s.chars(), s.summary()))
                .toList());
    }

    private static String defaultTitle(String filename) {
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String base = slash >= 0 ? filename.substring(slash + 1) : filename;
        int dot = base.lastIndexOf('.');
        base = dot > 0 ? base.substring(0, dot) : base;
        return base.isBlank() ? "未命名资料" : base.substring(0, Math.min(100, base.length()));
    }

}