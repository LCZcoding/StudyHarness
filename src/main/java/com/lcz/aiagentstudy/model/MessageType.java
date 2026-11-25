package com.lcz.aiagentstudy.model;

public enum MessageType {
//    'QUESTION','ANSWER','FEEDBACK','CHAT','NOTE'
    QUESTION,
    ANSWER,
    FEEDBACK,
    CHAT,
    NOTE            // V2：一次「笔记模式」回答就是一条 type=NOTE 的消息（plan.md C3）
}
