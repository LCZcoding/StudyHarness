package com.lcz.aiagentstudy.tools;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
public class ResourceDownloadToolTest {

    @Test
    public void testDownloadResource() {
        ResourceDownloadTool tool = new ResourceDownloadTool();
        String url = "https://www.codefather.cn/_next/static/media/vip.86643c96.svg";
        String fileName = "vip.svg";
        String result = tool.downloadResource(url, fileName);
        assertNotNull(result);
    }
}
