package com.lcz.aiagentstudy.tools;


import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
public class WebScrapingToolTest {

    @Test
    public void testScrapeWebPage() {
        WebScrapingTool tool = new WebScrapingTool();
        String url = "https://www.lichangzhuo.xyz";
        String result = tool.scrapeWebPage(url);
        assertNotNull(result);
    }
}
