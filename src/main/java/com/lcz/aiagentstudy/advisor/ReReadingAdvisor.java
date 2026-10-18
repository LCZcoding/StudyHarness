package com.lcz.aiagentstudy.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;

import java.util.Map;

/**
 * 重复阅读（Re-Reading）增强器，用于在发送请求前对用户输入进行模板增强。
 *
 * <p>将用户消息包装成增强文本（如 "如何脱单\nRead the question again: 如何脱单"），
 * 让 AI 对问题进行二次审视，提升回答准确性。
 */
public class ReReadingAdvisor implements BaseAdvisor {

	/** 默认的 Re2 增强模板。 */
	private static final String DEFAULT_RE2_ADVISE_TEMPLATE = """
			{re2_input_query}
			Read the question again: {re2_input_query}
			""";

	/** 当前使用的增强模板。 */
	private final String re2AdviseTemplate;

	/** 增强器的执行顺序，数值越小优先级越高。 */
	private int order = 0;

	public ReReadingAdvisor() {
		this(DEFAULT_RE2_ADVISE_TEMPLATE);
	}

	public ReReadingAdvisor(String re2AdviseTemplate) {
		this.re2AdviseTemplate = re2AdviseTemplate;
	}

	/**
	 * 在请求发送前对请求进行增强处理。
	 *
	 * @param chatClientRequest 当前请求
	 * @param advisorChain      增强器调用链
	 * @return 增强后的新请求对象
	 */
	@Override
	public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
		// PromptTemplate: Spring AI 的提示词模板工具，用于将变量填充到模板字符串中
		String augmentedUserText = PromptTemplate.builder()
				.template(this.re2AdviseTemplate)                   // 设置模板字符串，包含 {变量名} 占位符
				.variables(Map.of("re2_input_query", chatClientRequest.prompt().getUserMessage().getText()))  // 设置变量映射
				.build()                                            // 构建 PromptTemplate 实例
				.render();                                          // 渲染模板，将占位符替换为实际值
		//chatClientRequest.context().put("testThreadLocal", augmentedUserText);// 将增强后的文本存储到请求上下文，用于后续增强器使用

		// mutate(): 创建当前请求的可变副本
		return chatClientRequest.mutate()
				.prompt(chatClientRequest.prompt().augmentUserMessage(augmentedUserText))  // augmentUserMessage: 将新文本追加到用户消息末尾
				.build();                                           // 构建新的 ChatClientRequest
	}

	/**
	 * 在响应返回后进行处理。
	 *
	 * @param chatClientResponse 当前响应
	 * @param advisorChain       增强器调用链
	 * @return 处理后的响应对象
	 */
	@Override
	public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
		return chatClientResponse;
	}

	/**
	 * 返回增强器的执行顺序。
	 */
	@Override
	public int getOrder() {
		return this.order;
	}

	/**
	 * 设置增强器的执行顺序并返回自身。
	 */
	public ReReadingAdvisor withOrder(int order) {
		this.order = order;
		return this;
	}

}