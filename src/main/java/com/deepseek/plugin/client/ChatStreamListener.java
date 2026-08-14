package com.deepseek.plugin.client;

/**
 * 流式对话回调。回调可能发生在线程池线程，UI 更新需自行切回 EDT。
 */
public interface ChatStreamListener {

    /** 收到一段增量文本。 */
    void onDelta(String delta);

    /** 流结束，fullText 为完整回复。 */
    void onFinish(String fullText);

    /** 出错（网络、API 错误、取消等）。message 面向用户展示。 */
    void onError(String message, Throwable cause);
}
