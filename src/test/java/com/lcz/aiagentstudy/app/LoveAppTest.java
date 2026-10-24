package com.lcz.aiagentstudy.app;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

/**
 * {@link LoveApp} 的集成测试，验证多轮对话的会话记忆功能。
 */
@SpringBootTest
class LoveAppTest {

    @Resource
    private LoveApp loveApp;

    /**
     * 测试多轮对话：自我介绍 → 身份回忆 → 问题咨询。
     */
    @Test
    void doChat() {
        String chatId = UUID.randomUUID().toString();

        String message = "你好,我是张三";
        String answer = loveApp.doChat(message, chatId);

        message = "我是谁";
        answer = loveApp.doChat(message, chatId);

        message = "如何脱单";
        answer = loveApp.doChat(message, chatId);
    }

    @Test
    void doChatWithTools() {
//        // 测试联网搜索问题的答案 结果：模型没法比较主动的搜索，也可能是模型很懂上海，提示词要补充：可以联网搜索
//        testMessage("周末想带女朋友去上海约会，推荐几个适合情侣的小众打卡地？");
//
//        // 测试网页抓取：恋爱案例分析 结果：连接超时、网络问题？->模型问题
//        testMessage("我最近和对象吵架了，请尝试抓取网站（https://www.lichangzhuo.xyz）看看讲没讲是怎么解决矛盾的？");
//
//        // 测试资源下载：图片下载 结果：模型问题，已成功
//        testMessage("直接下载一张适合做手机壁纸的星空情侣图片为文件");
//
//        // 测试终端操作：执行代码 todo执行失败，AI response: 错误代码 1 表示 `which python3` 命令没有找到 Python 3 解释器，这进一步证实了 Python 3 可能未安装在你的系统上，或者它没有被添加到系统的环境变量中。
//        testMessage("执行 Python3 脚本来生成lichangzhuo.xyz网站的文章的内容分析报告、用户是Windows系统");
//
//        // 测试文件操作：保存用户档案
//        testMessage("保存我的恋爱档案为文件");
//
//        // 测试 PDF 生成
//        testMessage("生成一份‘七夕约会计划’PDF，包含餐厅预订、活动流程和礼物清单");

        // 测试日期时间工具
        testMessage("今天晚上12点前我想从开封到北京去约会，坐火车来得及吗？");
    }

    private void testMessage(String message) {
        String chatId = UUID.randomUUID().toString();
        String answer = loveApp.doChatWithTools(message, chatId);
        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithMcp() {
        String chatId = UUID.randomUUID().toString();
//        // 测试地图 MCP
//        String message = "我的另一半居住在上海静安区，请帮我找到 5 公里内合适的约会地点";
        // 测试图片搜索 MCP
        String message = "帮我搜索一些哄另一半开心的图片";
        String answer =  loveApp.doChatWithMcp(message, chatId);
        Assertions.assertNotNull(answer);
    }

}