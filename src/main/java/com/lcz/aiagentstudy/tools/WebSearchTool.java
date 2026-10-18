package com.lcz.aiagentstudy.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kong.unirest.core.HttpResponse;
import kong.unirest.core.Unirest;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public class WebSearchTool {

    private  String tavilyApiKey;

    public WebSearchTool(String tavilyApiKey) {
        this.tavilyApiKey = tavilyApiKey;
    }

    // 网络搜索，只能通过关键词搜索，而不是网址
    @Tool(description = "Search the web for the given query")
    public String searchWeb ( @ToolParam(description = "Search query keyword") String query) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("search_depth", "basic");
        payload.put("chunks_per_source", 3);
        payload.put("max_results", 1);
        payload.put("topic", "general");
//        payload.put("start_date", "2025-02-09");
//        payload.put("end_date", "2026-09-30");
        payload.put("include_published_date", false);
        payload.put("filter_by_published_date", false);
        payload.put("include_answer", false);
        payload.put("include_raw_content", false);
        payload.put("include_images", false);
        payload.put("include_image_descriptions", false);
        payload.put("include_favicon", false);
        payload.put("include_domains", List.of());
        payload.put("exclude_domains", List.of());
//        payload.put("include_domains_mode", "restrict");
        payload.put("language", "en");
        payload.put("filter_by_language", false);
        payload.put("auto_parameters", false);
        payload.put("exact_match", false);
        payload.put("include_usage", false);
        payload.put("safe_search", false);

        String json = mapper.writeValueAsString(payload);

        HttpResponse<String> response = null;
        try {
            response = Unirest.post("https://api.tavily.com/search")
                    .header("Authorization", "Bearer " + tavilyApiKey)
                    .header("Content-Type", "application/json")
                    .body(json)
                    .asString();
        } catch (Exception e) {
            return "Error searching web: " + e.getMessage();
        }
        return response.getBody();
    }

}