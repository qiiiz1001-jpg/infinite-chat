package cn.qingliao.aiservice.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface ConversationSummarizer {
    @SystemMessage(fromResource = "system-prompt/chat-summary.txt")
    String summarize(@UserMessage String conversation);
}
