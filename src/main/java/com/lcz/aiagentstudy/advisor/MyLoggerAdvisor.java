/*
 * Copyright 2023-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.lcz.aiagentstudy.advisor;

import lombok.extern.slf4j.Slf4j;

import reactor.core.publisher.Flux;

import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;

/**
 * 日志增强器，用于记录 AI 请求与响应的日志信息。
 *
 * <p>同时实现了 {@link CallAdvisor} 和 {@link StreamAdvisor}，
 * 支持同步调用和流式调用两种场景下的日志记录。
 */
@Slf4j
public class MyLoggerAdvisor implements CallAdvisor, StreamAdvisor {

	/** 增强器的执行顺序。 */
	private final int order = 0;

	/**
	 * 同步调用增强：在请求发送前和响应返回后记录日志。
	 *
	 * @param chatClientRequest 当前请求
	 * @param callAdvisorChain  增强器调用链
	 * @return 包含响应结果的 {@link ChatClientResponse}
	 */
	@Override
	public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
		logRequest(chatClientRequest);

		// nextCall: 将请求传递给链中的下一个 Advisor，最终到达模型
		ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(chatClientRequest);

		logResponse(chatClientResponse);

		return chatClientResponse;
	}

	/**
	 * 流式调用增强：记录请求日志，流式响应聚合完成后统一记录响应日志。
	 *
	 * @param chatClientRequest   当前请求
	 * @param streamAdvisorChain  流式增强器调用链
	 * @return 聚合后的流式响应 Flux
	 */
	@Override
	public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
			StreamAdvisorChain streamAdvisorChain) {
		logRequest(chatClientRequest);

		// nextStream: 将请求传递给链中的下一个流式 Advisor
		Flux<ChatClientResponse> chatClientResponses = streamAdvisorChain.nextStream(chatClientRequest);

		// ChatClientMessageAggregator: 将流式响应块聚合成完整的 ChatClientResponse
		// aggregateChatClientResponse: 聚合完成后调用回调函数
		return new ChatClientMessageAggregator().aggregateChatClientResponse(chatClientResponses, this::logResponse);
	}

	/**
	 * 记录请求日志。
	 */
	protected void logRequest(ChatClientRequest request) {
		// request.prompt(): 获取请求中的 Prompt 对象
		// getUserMessage(): 获取用户发送的消息
		log.info("AI request: {}", request.prompt().getUserMessage());
	}

	/**
	 * 记录响应日志。
	 */
	protected void logResponse(ChatClientResponse chatClientResponse) {
		// chatResponse(): 获取底层 ChatResponse 对象
		// getResult(): 获取第一个生成结果
		// getOutput(): 获取 AI 生成的消息对象
		// getText(): 提取消息的文本内容
		log.info("AI response: {}", chatClientResponse.chatResponse().getResult().getOutput().getText());
	}

	/**
	 * 返回增强器的名称。
	 */
	@Override
	public String getName() {
		return this.getClass().getSimpleName();
	}

	/**
	 * 返回增强器的执行顺序。
	 */
	@Override
	public int getOrder() {
		return this.order;
	}

}