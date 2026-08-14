package com.deepseek.plugin.client;

/**
 * OpenAI 兼容的对话消息。
 */
public class ChatMessage {
    public final String role;    // system / user / assistant
    public final String content;

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }
}
