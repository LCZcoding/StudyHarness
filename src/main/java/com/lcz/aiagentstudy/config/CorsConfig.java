package com.lcz.aiagentstudy.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局跨域配置
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry){
        // 覆盖所有请求
        registry.addMapping("/**")// 表示对所有的 URL 路径（包括 api/ai/manus/chat 等所有接口）生效。
                // 允许发送 Cookie. 允许跨域请求携带凭证（如 Cookie、Session ID、JWT Token、Authorization 头）。
                .allowCredentials(true)
                // 放行哪些域名（必须用 patterns，否则 * 会和 allowCredentials 冲突）
                .allowedOriginPatterns("*")// 允许所有源（域名/IP/端口）访问。
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")// 允许前端在请求头中携带任何自定义字段（比如 Content-Type: application/json, Authorization 等）。
                .exposedHeaders("*");// 如果后端想让前端读取 Content-Disposition（比如文件下载时读取文件名），或者自定义的 Token 头，必须在这里暴露出来。设为 * 就是全部暴露。
    }
}
