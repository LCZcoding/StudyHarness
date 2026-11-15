package com.lcz.aiagentstudy.repository;

import com.lcz.aiagentstudy.model.AnnotationRow;
import com.lcz.aiagentstudy.model.TargetType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T003 集成测试：打真实库（t_annotation 已在 T001 建好），类上 @Transactional 跑完自动回滚。
 * 覆盖 9 个方法，外加 I1/I2/I4 相关不变量：父先于子、归属隔离、链深、级联删除。
 */
@SpringBootTest
@Transactional
class AnnotationRepositoryTest {

    private static final String TARGET_ID = "00000000-0000-0000-0000-000000000001";

    @Autowired
    private AnnotationRepository repo;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID newUser() {
        return jdbc.queryForObject(
                "insert into t_user(username, password_hash) values (?, ?) returning id",
                UUID.class, UUID.randomUUID().toString(), "test-hash");
    }

    private AnnotationRepository.NewAnnotation top(String quote, String question, String answer) {
        return new AnnotationRepository.NewAnnotation(
                TargetType.SECTION, TARGET_ID, null, "p-1", quote, question, answer);
    }

    private AnnotationRepository.NewAnnotation child(long parentId, String quote,
                                                     String question, String answer) {
        return new AnnotationRepository.NewAnnotation(
                TargetType.SECTION, TARGET_ID, parentId, "p-1", quote, question, answer);
    }

    @Test
    void insert_findByTarget_findById_countByTarget() {
        UUID user = newUser();
        long parent = repo.insert(user, top("召回 topK 片段", "topK 是什么？", "取前 K 个片段。"));
        long child = repo.insert(user, child(parent, "余弦相似度", "怎么算？", "夹角的余弦。"));

        assertThat(parent).isPositive();
        assertThat(child).isGreaterThan(parent);

        List<AnnotationRow> rows = repo.findByTarget(user, TargetType.SECTION, TARGET_ID);
        assertThat(rows).hasSize(2);
        // created_at 在同一事务内可能完全相同，必须靠 id 让父排在子前
        assertThat(rows.get(0).id()).isEqualTo(parent);
        assertThat(rows.get(0).parentId()).isNull();
        assertThat(rows.get(0).edited()).isFalse();
        assertThat(rows.get(1).id()).isEqualTo(child);
        assertThat(rows.get(1).parentId()).isEqualTo(parent);
        assertThat(rows.get(1).blockKey()).isEqualTo("p-1");

        var entity = repo.findById(child).orElseThrow();
        assertThat(entity.userId()).isEqualTo(user);
        assertThat(entity.targetType()).isEqualTo(TargetType.SECTION);
        assertThat(entity.targetId()).isEqualTo(TARGET_ID);
        assertThat(entity.answer()).isEqualTo("夹角的余弦。");
        assertThat(entity.toRow().id()).isEqualTo(child);

        assertThat(repo.countByTarget(user, TargetType.SECTION, TARGET_ID)).isEqualTo(2);
        // 纵深防御：换一个 userId 查不到任何批注
        assertThat(repo.findByTarget(UUID.randomUUID(), TargetType.SECTION, TARGET_ID)).isEmpty();
        assertThat(repo.findById(999999L)).isEmpty();
    }

    @Test
    void belongsTo_本人可查他人不可() {
        UUID owner = newUser();
        UUID other = newUser();
        long id = repo.insert(owner, top("原句", "问题", "答案"));

        assertThat(repo.belongsTo(id, owner)).isTrue();
        assertThat(repo.belongsTo(id, other)).isFalse();
        assertThat(repo.belongsTo(999999L, owner)).isFalse();
    }

    @Test
    void depthOf_与_countDescendants_覆盖三层链() {
        UUID user = newUser();
        long a = repo.insert(user, top("a", "q", "a1"));
        long b = repo.insert(user, child(a, "b", "q", "a2"));
        long c = repo.insert(user, child(b, "c", "q", "a3"));

        assertThat(repo.depthOf(a)).isEqualTo(1);
        assertThat(repo.depthOf(b)).isEqualTo(2);
        assertThat(repo.depthOf(c)).isEqualTo(3);
        assertThat(repo.depthOf(999999L)).isZero();

        assertThat(repo.countDescendants(a)).isEqualTo(3);
        assertThat(repo.countDescendants(b)).isEqualTo(2);
        assertThat(repo.countDescendants(c)).isEqualTo(1);
        assertThat(repo.countDescendants(999999L)).isZero();
    }

    @Test
    void updateAnswer_只改答案_deleteById_级联删后代() {
        UUID user = newUser();
        long parent = repo.insert(user, top("原句", "问题", "原答案"));
        long child = repo.insert(user, child(parent, "陌生词", "追问", "子答案"));

        assertThat(repo.updateAnswer(child, "改过的答案")).isEqualTo(1);
        var updated = repo.findById(child).orElseThrow();
        assertThat(updated.answer()).isEqualTo("改过的答案");
        assertThat(updated.edited()).isTrue();
        assertThat(updated.quote()).isEqualTo("陌生词");   // quote 不可改
        assertThat(repo.findById(parent).orElseThrow().edited()).isFalse();  // 父未被误改

        assertThat(repo.countDescendants(parent)).isEqualTo(2);
        assertThat(repo.deleteById(parent)).isEqualTo(1);
        assertThat(repo.findById(parent)).isEmpty();
        assertThat(repo.findById(child)).isEmpty();
        assertThat(repo.countByTarget(user, TargetType.SECTION, TARGET_ID)).isZero();
    }
}
