package com.lcz.yuaiagent.agent;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class YuManusTest {
    @Resource
    private LiManus liManus;

    @Test
    void testLiManus() {
        String userPrompt = """
                我的另一半居住在上海静安区，请帮我找到 5 公里内合适的约会地点，
                并结合一些网络上的图片，制定一份详细的约会计划，
                并以 PDF 格式输出""";
        String answer = liManus.run(userPrompt);
        Assertions.assertNotNull(answer);
    }
}