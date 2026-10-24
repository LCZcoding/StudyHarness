package com.lcz.aiagentstudy.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Configuration
public class ToolRegistration {

    @Value("${tavily.api-key}")
    private String tavilyApiKey;

    // 工厂模式 注册所有工具,隐藏实现细节
    // 依赖注入模式 注入 tavilyApiKey
    // 注册模式 集中注册和管理所有工具到 Spring 容器中
    // 适配器模式 适配不同工具的接口,使它们能够统一处理
    // ObjectProvider 延迟获取 MCP 客户端工具：images-search-mcp-server(8127) 未启动时不影响本地工具注册
    @Bean
    public ToolCallback[] allTools(ObjectProvider<SyncMcpToolCallbackProvider> mcpToolCallbackProvider) {
        FileOperationTool fileOperationTool = new FileOperationTool();
        WebSearchTool webSearchTool = new WebSearchTool(tavilyApiKey);
        WebScrapingTool webScrapingTool = new WebScrapingTool();
        ResourceDownloadTool resourceDownloadTool = new ResourceDownloadTool();
        TerminalOperationTool terminalOperationTool = new TerminalOperationTool();
        PDFGenerationTool pdfGenerationTool = new PDFGenerationTool();
        DateTimeTool dateTimeTool = new DateTimeTool();
        TerminateTool terminateTool = new TerminateTool();

        // ToolCallbacks.from() 会扫描每个传入对象的 @Tool 方法，为每个方法创建一个 MethodToolCallback 对象
        List<ToolCallback> tools = new ArrayList<>(Arrays.asList(ToolCallbacks.from( // 适配器 将所有工具适配为 ToolCallback[] 类型
            fileOperationTool,
            webSearchTool,
            webScrapingTool,
            resourceDownloadTool,
            terminalOperationTool,
            pdfGenerationTool,
            dateTimeTool,
            terminateTool
        )));
        // 新版本把mcp和本地工具的自动注册给删除了，只有旧版的starter才有。新版本需要手动实现MCP工具的注册
        // 合并通过 MCP(SSE) 协议接入的远程工具，例如 images-search-mcp-server 的 searchImage
        //ObjectProvider<SyncMcpToolCallbackProvider>延迟取 调`getIfAvailable()` 时才真正去取`SyncMcpToolCallbackProvider`
        SyncMcpToolCallbackProvider mcpProvider = mcpToolCallbackProvider.getIfAvailable();// 获取 MCP 客户端工具提供器
        if (mcpProvider != null) {
            try {
                ToolCallback[] mcpTools = mcpProvider.getToolCallbacks();// 从 MCP 服务获取远程工具
                if (mcpTools != null && mcpTools.length > 0) {
                    tools.addAll(Arrays.asList(mcpTools));
                    log.info("已加载 MCP 远程工具 {} 个: {}", mcpTools.length,
                            Arrays.stream(mcpTools).map(t -> t.getToolDefinition().name()).toList());
                }
            } catch (Exception e) {
                // MCP 服务未启动或工具列表拉取失败时降级为仅使用本地工具
                log.warn("加载 MCP 远程工具失败，降级为仅使用本地工具: {}", e.getMessage());
            }
        }

        return tools.toArray(new ToolCallback[0]);
    }

    /**
     * 聊天可用的安全工具集：只读外部信息，不碰服务器。
     * 刻意不包含 TerminalOperationTool / FileOperationTool / ResourceDownloadTool / PDFGenerationTool：
     * 这几个都会在服务器上产生副作用（执行命令、写文件），不该由一次聊天触发。
     */
    @Bean(name = "chatTools")
    public ToolCallback[] chatTools() {
        WebSearchTool webSearchTool = new WebSearchTool(tavilyApiKey);
        WebScrapingTool webScrapingTool = new WebScrapingTool();
        DateTimeTool dateTimeTool = new DateTimeTool();
        return ToolCallbacks.from(webSearchTool, webScrapingTool, dateTimeTool);
    }
}
