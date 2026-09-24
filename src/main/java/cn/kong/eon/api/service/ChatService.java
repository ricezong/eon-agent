package cn.kong.eon.api.service;

import cn.kong.eon.tool.InteractionAnswer;
import cn.kong.eon.api.dto.ChatRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 对话编排服务。负责会话身份解析（新建/续接）与引擎执行。 */
public interface ChatService {

    /**
     * 流式对话。sessionId 为空时自动创建新会话，否则续接已有会话
     */
    SseEmitter chat(ChatRequest request, String userId);

    /** 投递提问答案。会话不在等待回答时返回 false。 */
    boolean answer(String sessionId, InteractionAnswer answer);

    boolean interrupt(String sessionId);
}
