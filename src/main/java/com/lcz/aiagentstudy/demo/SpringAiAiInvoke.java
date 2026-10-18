//package com.lcz.aiagentstudy.demo;
//
//import jakarta.annotation.Resource;
//import org.springframework.ai.chat.messages.AssistantMessage;
//import org.springframework.ai.chat.model.ChatModel;
//import org.springframework.ai.chat.prompt.Prompt;
//import org.springframework.boot.CommandLineRunner;
//import org.springframework.stereotype.Component;
//
//@Component
//public class SpringAiAiInvoke implements CommandLineRunner {//自动执行一次
//
//    @Resource//该注解默认优先按照名称匹配
//    private ChatModel dashScopeChatModel;//名称绝对不能改
//
//    @Override
//    public void run(String... args) throws Exception {
//        AssistantMessage output = dashScopeChatModel.call(new Prompt("你好"))
//                .getResult()
//                .getOutput();
//        System.out.println(output.getText());
//    }
//}