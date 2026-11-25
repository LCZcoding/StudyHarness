package com.lcz.aiagentstudy.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T002 契约测试：纯 POJO，不加载 Spring 上下文、不连库、不调模型。
 * 对照 specs/001-annotatable-note/plan.md 5.2 #4 的响应元素。
 */
class AnnotationRowTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void targetType_onlySectionAndNote_invalidValueRejected() {
        assertThat(TargetType.values()).containsExactly(TargetType.SECTION, TargetType.NOTE);

        assertThatThrownBy(() -> mapper.readValue("\"BLOCK\"", TargetType.class))
                .isInstanceOf(Exception.class);
    }

    @Test
    void topLevelAnnotation_jsonFields_matchContract() throws Exception {
        AnnotationRow row = new AnnotationRow(
                12L, null, "p-1", "召回 topK 片段", "topK 是什么？", "topK 是取前 K 个最相似的片段。",
                false, OffsetDateTime.parse("2026-11-14T12:00:00+08:00"));

        JsonNode json = mapper.valueToTree(row);

        Set<String> fields = new HashSet<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(
                "id", "parentId", "blockKey", "quote", "question", "answer", "edited", "createdAt");
        // 归属字段不得下发（FR-12）
        assertThat(fields).doesNotContain("userId", "targetType", "targetId");

        assertThat(json.get("id").asLong()).isEqualTo(12L);
        assertThat(json.get("parentId").isNull()).isTrue();
        assertThat(json.get("edited").asBoolean()).isFalse();
        assertThat(json.get("createdAt").asText()).contains("2026-11-14");
    }

    @Test
    void childAnnotation_parentIdAndEdited_serialized() throws Exception {
        AnnotationRow child = new AnnotationRow(
                13L, 12L, "p-1", "余弦相似度", "相似度怎么算？", "夹角的余弦值。",
                true, OffsetDateTime.parse("2026-11-14T12:01:00+08:00"));

        JsonNode json = mapper.valueToTree(child);

        assertThat(json.get("parentId").asLong()).isEqualTo(12L);
        assertThat(json.get("blockKey").asText()).isEqualTo("p-1");
        assertThat(json.get("edited").asBoolean()).isTrue();
    }
}
