package com.deepseek.plugin.client;

import com.deepseek.plugin.settings.DeepSeekState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DeepSeek API 客户端（OpenAI 兼容格式），支持 SSE 流式输出。
 * 流式调用返回的 CompletableFuture 可通过 cancel(true) 中断请求。
 */
public class DeepSeekClient {

    private static final Logger LOG = Logger.getInstance(DeepSeekClient.class);
    private static volatile DeepSeekClient instance;

    public static DeepSeekClient getInstance() {
        DeepSeekClient local = instance;
        if (local == null) {
            synchronized (DeepSeekClient.class) {
                local = instance;
                if (local == null) {
                    local = new DeepSeekClient();
                    instance = local;
                }
            }
        }
        return local;
    }

    private final HttpClient httpClient;

    public DeepSeekClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                // 绕过 IDE 的 HTTP 代理（PAC 等）直连 DeepSeek API：
                // JDK HttpClient 默认不读取系统代理，但 IntelliJ 平台会设置全局 ProxySelector，
                // 导致请求被 PAC 代理接管而挂起。显式禁用代理后走直连。
                .proxy(ProxySelector.of(null))
                .build();
    }

    public static final class StreamRequest {
        public String apiKey;
        public String baseUrl;
        public String model;
        public double temperature;
        public int maxTokens;
        /** OpenAI 兼容 stop tokens；为 null/空时不传（不截断）。 */
        public java.util.List<String> stop;
        public List<ChatMessage> messages;
        public boolean stream = true;
        /**
         * 推理强度：null/空 = 不传该参数（交给 API 默认）；
         * "none" = 显式关闭推理；"low"/"medium"/"high" = 开启推理（先输出 reasoning_content，
         * 客户端会跳过它，只展示 content，但首字符出现时间明显变长）。
         */
        public String reasoningEffort;
    }

    /**
     * 发起一次流式对话。
     *
     * @return 完成（携带完整回复文本）或异常的 future；调用方可 cancel(true) 中断。
     */
    public CompletableFuture<String> chatStream(StreamRequest req, ChatStreamListener listener) {
        CompletableFuture<String> result = new CompletableFuture<>();
        String endpoint = normalizeBaseUrl(req.baseUrl) + "/chat/completions";
        LOG.info("chatStream: endpoint=" + endpoint + " model=" + req.model + " keyLen=" + req.apiKey.length());

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(300))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + req.apiKey)
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(buildBody(req), StandardCharsets.UTF_8));

        httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> {
                    LOG.info("chatStream: response status=" + response.statusCode());
                    handleResponse(response, req, listener, result);
                })
                .exceptionally(ex -> {
                    LOG.warn("chatStream: request failed", ex);
                    if (result.isCancelled()) {
                        listener.onError("请求已取消", null);
                        result.completeExceptionally(ex);
                        return null;
                    }
                    String msg = ex instanceof java.net.http.HttpTimeoutException
                            ? "请求超时，请检查网络或稍后重试"
                            : "网络请求失败: " + rootMessage(ex);
                    listener.onError(msg, ex);
                    result.completeExceptionally(ex);
                    return null;
                });
        return result;
    }

    /** 非流式单次请求，用于“测试连接”。返回完整回复文本。 */
    public CompletableFuture<String> chatOnce(StreamRequest req) {
        String endpoint = normalizeBaseUrl(req.baseUrl) + "/chat/completions";
        req.stream = false;
        LOG.info("chatOnce: endpoint=" + endpoint + " model=" + req.model + " keyLen=" + req.apiKey.length());
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + req.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(buildBody(req), StandardCharsets.UTF_8))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    LOG.info("chatOnce: response status=" + response.statusCode() + " body=" + response.body().substring(0, Math.min(200, response.body().length())));
                    String body = response.body();
                    if (response.statusCode() != 200) {
                        throw new ApiException(response.statusCode(), extractErrorMessage(body));
                    }
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    JsonObject message = json.getAsJsonArray("choices").get(0)
                            .getAsJsonObject().getAsJsonObject("message");
                    // 推理模型（如 deepseek-v4-flash/pro）的回复在 reasoning_content，
                    // content 可能为空或缺失，不能抛异常
                    if (message != null && message.has("content")
                            && !message.get("content").isJsonNull()) {
                        return message.get("content").getAsString();
                    }
                    return "";
                });
    }

    private void handleResponse(HttpResponse<java.util.stream.Stream<String>> response,
                                StreamRequest req,
                                ChatStreamListener listener,
                                CompletableFuture<String> result) {
        int status = response.statusCode();
        if (status != 200) {
            String errBody = response.body().limit(4000).reduce("", String::concat);
            String msg = "API 错误 (" + status + "): " + extractErrorMessage(errBody);
            if (status == 401) msg = "API Key 无效或未授权 (401)，请在设置中检查";
            if (status == 429) msg = "请求过于频繁或额度不足 (429)";
            listener.onError(msg, null);
            result.completeExceptionally(new ApiException(status, msg));
            return;
        }

        StringBuilder full = new StringBuilder();
        AtomicBoolean done = new AtomicBoolean(false);
        int chunkCount = 0;
        int contentChunkCount = 0;
        try (java.util.stream.Stream<String> lines = response.body()) {
            java.util.Iterator<String> it = lines.iterator();
            while (it.hasNext()) {
                if (result.isCancelled()) {
                    done.set(true);
                    break;
                }
                String line = it.next();
                if (line == null || !line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).trim();
                if ("[DONE]".equals(payload)) {
                    break;
                }
                try {
                    JsonObject obj = JsonParser.parseString(payload).getAsJsonObject();
                    JsonArray choices = obj.getAsJsonArray("choices");
                    if (choices == null || choices.isEmpty()) continue;
                    JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                    if (delta == null) continue;
                    chunkCount++;
                    if (delta.has("content")) {
                        String chunk = delta.get("content").getAsString();
                        if (chunk != null && !chunk.isEmpty()) {
                            contentChunkCount++;
                            full.append(chunk);
                            listener.onDelta(chunk);
                        }
                    } else if (delta.has("reasoning_content")) {
                        LOG.info("chatStream: delta has only reasoning_content, len="
                                + delta.get("reasoning_content").getAsString().length()
                                + " (totalChunks=" + chunkCount + ")");
                    }
                } catch (Exception ignore) {
                    // 个别非 JSON 事件（如 keep-alive）直接跳过
                }
            }
        } catch (Exception ex) {
            done.set(true);
            listener.onError("读取响应流失败: " + rootMessage(ex), ex);
            result.completeExceptionally(ex);
            return;
        }
        LOG.info("chatStream: finished, totalChunks=" + chunkCount
                + " contentChunks=" + contentChunkCount
                + " fullTextLen=" + full.length());

        if (!done.get()) {
            listener.onFinish(full.toString());
            result.complete(full.toString());
        }
    }

    private String buildBody(StreamRequest req) {
        JsonObject root = new JsonObject();
        root.addProperty("model", req.model);
        root.addProperty("stream", req.stream);
        root.addProperty("temperature", req.temperature);
        root.addProperty("max_tokens", req.maxTokens);
        // stop tokens：单行场景用 ["\n"] 让模型输出一行即停，避免拖沓；null/空则省略
        if (req.stop != null && !req.stop.isEmpty()) {
            JsonArray stopArr = new JsonArray();
            for (String s : req.stop) {
                stopArr.add(s);
            }
            root.add("stop", stopArr);
        }
        // 推理模式：由调用方显式指定才传。
        // "none"=关闭推理（deepseek-v4 系列默认是推理模型，流式补全先输出 reasoning_content
        // 会迟迟拿不到 content，所以补全默认显式关掉）；"low/medium/high"=开启推理，
        // 输出质量可能更好，但首字符明显变慢（reasoning_content 会被流式解析跳过，不展示）。
        if (req.reasoningEffort != null && !req.reasoningEffort.isBlank()) {
            root.addProperty("reasoning_effort", req.reasoningEffort);
        }

        JsonArray messages = new JsonArray();
        for (ChatMessage m : req.messages) {
            JsonObject jo = new JsonObject();
            jo.addProperty("role", m.role);
            jo.addProperty("content", m.content);
            messages.add(jo);
        }
        root.add("messages", messages);
        return root.toString();
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String url = (baseUrl == null || baseUrl.isBlank()) ? "https://api.deepseek.com" : baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) return "无响应内容";
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("error") && json.getAsJsonObject("error").has("message")) {
                return json.getAsJsonObject("error").get("message").getAsString();
            }
        } catch (Exception ignore) {
            // 不是 JSON，返回原文截断
        }
        return body.length() > 500 ? body.substring(0, 500) : body;
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null) t = t.getCause();
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    public static void notifyError(String title, String content) {
        Notifications.Bus.notify(new Notification("DeepSeek Java Expert", title, content, NotificationType.ERROR));
    }

    public static class ApiException extends RuntimeException {
        public final int statusCode;

        public ApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}
