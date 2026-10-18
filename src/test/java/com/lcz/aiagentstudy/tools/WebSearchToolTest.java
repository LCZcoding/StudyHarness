package com.lcz.aiagentstudy.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest
class WebSearchToolTest {
    @Value("${tavily.api-key}")
    private  String tavilyApiKey;

    @Test
    void searchWeb() throws JsonProcessingException {
        WebSearchTool tool = new WebSearchTool(tavilyApiKey);
        String query = "程序员鱼皮编程导航 codefather.cn";
        String result = tool.searchWeb(query);
        assertNotNull(result);
    }
}
