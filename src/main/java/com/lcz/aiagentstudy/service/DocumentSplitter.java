package com.lcz.aiagentstudy.service;

import com.lcz.aiagentstudy.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 整篇文档 -> 有序章节。纯函数：文本进、章节列表出，不碰数据库、不调模型。
 * 有 `#` / `##` 就按标题切；完全没有这类标题就按空行段落切。
 */
@Component
public class DocumentSplitter {

    /** 段内正文到这个量级就可以断开。 */
    static final int TARGET_MIN = 8_000;
    /** 单章硬上限，对齐 QuizService.MATERIAL_LIMIT。 */
    static final int SECTION_MAX = 12_000;
    /** 末尾残段小于这个值就并进前一段。 */
    static final int TAIL_MIN = 2_000;
    static final int MAX_SECTIONS = 30;

    private static final int TITLE_MAX = 100;
    private static final int TITLE_PROBE = 30;
    private static final int TITLE_PROBE_MIN = 10;
    private static final String SENTENCE_MARKS = "。！？；.!?;\n";

    public record Section(String title, String content) {
    }

    public List<Section> split(String docTitle, String text) {
        List<Section> packed = pack(toUnits(text));

        if (packed.size() > MAX_SECTIONS) {
            throw new BadRequestException("文档切分后超过 " + MAX_SECTIONS + " 章（当前 "
                    + packed.size() + " 章），请拆分后分次上传");
        }
        if (packed.size() == 1) {
            // 单章文档不编标题，直接用文档名
            return List.of(new Section(truncate(docTitle, TITLE_MAX), packed.get(0).content()));
        }

        List<Section> result = new ArrayList<>(packed.size());
        for (int i = 0; i < packed.size(); i++) {
            Section section = packed.get(i);
            String title = section.title() == null || section.title().isBlank()
                    ? fallbackTitle(section.content(), i + 1)
                    : section.title();
            result.add(new Section(truncate(title, TITLE_MAX), section.content()));
        }
        return result;
    }

    // ---------------- 第一步：原文 -> 小块 ----------------

    /** 中间态。标题可能为 null：段落模式下没有标题可用。 */
    private record Unit(String title, String content) {
    }

    private List<Unit> toUnits(String text) {
        List<String> lines = List.of(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
        boolean byHeading = hasLevel12Heading(lines);

        List<Unit> units = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        String parentTitle = null;
        String title = null;
        boolean inFence = false;

        for (String line : lines) {
            String trimmed = line.trim();
            boolean fence = trimmed.startsWith("```");

            if (byHeading && !inFence && !fence && isLevel12Heading(trimmed)) {
                flush(units, title, body);
                String heading = cleanHeading(trimmed);
                if (headingLevel(trimmed) == 1) {
                    parentTitle = heading;
                    title = heading;
                } else {
                    // 二级标题带上父级标题，避免「第 2 节」在列表里重名
                    title = parentTitle == null ? heading : parentTitle + " · " + heading;
                }
            }
            body.append(line).append('\n');

            if (fence) {
                inFence = !inFence;
            } else if (!byHeading && !inFence && trimmed.isEmpty()) {
                // 段落模式：空行即边界。代码块里的空行不算
                flush(units, null, body);
            }
        }
        flush(units, title, body);
        return hardCut(units);
    }

    private boolean hasLevel12Heading(List<String> lines) {
        boolean inFence = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
            } else if (!inFence && isLevel12Heading(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private int headingLevel(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        return level;
    }

    /** 只认 `# 标题` 与 `## 标题`；`###` 及更深留在正文里，不切段。 */
    private boolean isLevel12Heading(String line) {
        int level = headingLevel(line);
        if (level < 1 || level > 2 || level >= line.length()) {
            return false;
        }
        char sep = line.charAt(level);
        return sep == ' ' || sep == '\t';
    }

    private String cleanHeading(String line) {
        return line.substring(headingLevel(line)).strip();
    }

    private void flush(List<Unit> units, String title, StringBuilder body) {
        String content = body.toString().strip();
        body.setLength(0);
        if (!content.isEmpty()) {
            units.add(new Unit(title, content));
        }
    }

    /** 单个块超过 SECTION_MAX（超长代码块、表格、没有空行的长段落）时按句子边界硬切。 */
    private List<Unit> hardCut(List<Unit> units) {
        List<Unit> result = new ArrayList<>(units.size());
        for (Unit unit : units) {
            List<String> chunks = chunks(unit.content());
            for (int i = 0; i < chunks.size(); i++) {
                String title = unit.title();
                if (title != null && chunks.size() > 1) {
                    title = title + "（" + (i + 1) + "）";
                }
                result.add(new Unit(title, chunks.get(i)));
            }
        }
        return result;
    }

    private List<String> chunks(String content) {
        List<String> chunks = new ArrayList<>();
        String rest = content.strip();
        while (rest.length() > SECTION_MAX) {
            int cut = lastSentenceEnd(rest);
            if (cut <= 0) {
                cut = SECTION_MAX;   // 整段没有句末标点，只能按字符切
            }
            chunks.add(rest.substring(0, cut).strip());
            rest = rest.substring(cut).strip();
        }
        if (!rest.isEmpty()) {
            chunks.add(rest);
        }
        return chunks;
    }

    /** 从 SECTION_MAX 往前找最近的句末标点；找不到返回 -1。 */
    private int lastSentenceEnd(String text) {
        int floor = SECTION_MAX / 2;
        for (int i = SECTION_MAX - 1; i >= floor; i--) {
            if (SENTENCE_MARKS.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return -1;
    }

    // ---------------- 第二步：小块 -> 章节 ----------------

    private List<Section> pack(List<Unit> units) {
        List<Unit> merged = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        String title = null;

        for (Unit unit : units) {
            // 两条断开条件：装不下（硬上限）优先，其次才看"够不够长（TARGET_MIN）"
            boolean overflow = body.length() + unit.content().length() > SECTION_MAX;
            boolean longEnough = body.length() >= TARGET_MIN;

            if (body.length() > 0 && (overflow || longEnough)) {
                merged.add(new Unit(title, body.toString().strip()));
                body.setLength(0);
                title = null;
            }
            if (title == null) {
                title = unit.title();
            }
            if (body.length() > 0) {
                body.append("\n\n");
            }
            body.append(unit.content());
        }
        if (body.length() > 0) {
            merged.add(new Unit(title, body.toString().strip()));
        }

        // 末尾残段：放得下并进前一段，放不下就自己成一章（宁可短，不要超 12000）
        if (merged.size() > 1) {
            Unit last = merged.get(merged.size() - 1);
            Unit prev = merged.get(merged.size() - 2);
            if (last.content().length() < TAIL_MIN
                    && prev.content().length() + last.content().length() <= SECTION_MAX) {
                merged.remove(merged.size() - 1);
                merged.remove(merged.size() - 1);
                merged.add(new Unit(prev.title(), prev.content() + "\n\n" + last.content()));
            }
        }
        return merged.stream().map(u -> new Section(u.title(), u.content())).toList();
    }

    /** 没有标题的段落：取首行前 30 字；太短或为空时回退成「第 N 部分」。 */
    private String fallbackTitle(String content, int sectionNo) {
        String firstLine = content.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("")
                .replaceFirst("^#+\\s*", "");
        String probe = truncate(firstLine, TITLE_PROBE);
        return probe.length() < TITLE_PROBE_MIN ? "第 " + sectionNo + " 部分" : probe;
    }

    private String truncate(String text, int max) {
        String flat = text == null ? "" : text.strip();
        return flat.length() <= max ? flat : flat.substring(0, max);
    }
}