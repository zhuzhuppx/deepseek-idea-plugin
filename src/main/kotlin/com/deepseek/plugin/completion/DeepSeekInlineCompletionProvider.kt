package com.deepseek.plugin.completion

import com.deepseek.plugin.client.ChatMessage
import com.deepseek.plugin.client.DeepSeekClient
import com.deepseek.plugin.context.CodeContextCollector
import com.deepseek.plugin.prompt.JavaExpertPrompt
import com.deepseek.plugin.settings.DeepSeekState
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.TypingEvent
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionTextElement
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.markup.TextAttributes
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 行内补全 Provider（Kotlin 适配器）。
 *
 * 2025.3 平台的 InlineCompletionProvider 是 Kotlin 协程接口（suspend + value class 方法），
 * Java 无法直接实现，因此用这一小段 Kotlin 适配器桥接到 Java 侧的业务代码。
 */
class DeepSeekInlineCompletionProvider : InlineCompletionProvider {

    private val LOG = Logger.getInstance(DeepSeekInlineCompletionProvider::class.java)

    override val id: InlineCompletionProviderID =
        InlineCompletionProviderID("deepseek-java-expert")

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        LOG.info("getSuggestion called, lang=" + (request.file?.language) + " event=" + request.event)
        val state = DeepSeekState.getInstance()
        if (!state.completionEnabled) {
            LOG.info("getSuggestion: completion disabled")
            return emptySuggestion()
        }

        val editor = request.editor ?: return emptySuggestion()
        val file = request.file ?: return emptySuggestion()
        // v1 仅支持 Java 专家模式
        if (file.language != JavaLanguage.INSTANCE) {
            LOG.info("getSuggestion: not java, lang=" + file.language)
            return emptySuggestion()
        }
        val project = editor.project ?: return emptySuggestion()
        if (project.isDisposed) return emptySuggestion()

        val configuredKey = state.apiKey.trim()
        if (configuredKey.isEmpty()) {
            LOG.info("getSuggestion: no api key")
            return emptySuggestion()   // 未配置 Key 时静默不补全
        }

        // 261 平台补全在协程后台线程执行（Inline Edit Request Executor），
        // 读取编辑器/PSI 必须在 ReadAction 内，否则抛 IllegalStateException
        val context = com.intellij.openapi.application.ReadAction.compute<com.deepseek.plugin.context.CodeContextCollector.EditorContext, RuntimeException> {
            try {
                CodeContextCollector.collect(editor, file)
            } catch (t: Throwable) {
                LOG.warn("getSuggestion: collect failed", t)
                null
            }
        } ?: return emptySuggestion()

        // ===== 场景识别：单一入口 =====
        val scene = com.deepseek.plugin.completion.CompletionSceneDetector.detect(context)
        LOG.info("getSuggestion: scene=" + scene.id)
        if (scene == com.deepseek.plugin.completion.CompletionScene.NONE) {
            return emptySuggestion()
        }
        val prompt = JavaExpertPrompt.buildScenePrompt(scene, context)

        // 读取剪贴板内容作为额外线索，帮助模型猜用户下一步想输入什么
        val clipboardText = readClipboardText()
        val finalPrompt = if (clipboardText != null && clipboardText.isNotBlank()) {
            prompt + "\n\n【剪贴板内容（可能是你即将粘贴/参考的文本，仅供参考）】\n" + clipboardText.take(2000)
        } else {
            prompt
        }
        if (clipboardText != null && clipboardText.isNotBlank()) {
            LOG.info("getSuggestion: clipboard len=" + clipboardText.length)
        }
        val system = JavaExpertPrompt.buildCompletionSystemPrompt(project)
        val messages = listOf(
            ChatMessage.system(system),
            ChatMessage.user(finalPrompt)
        )

        // ===== 防抖：IDE 每次输入会取消前一次 getSuggestion 协程，
        // 用短延迟(100ms)在协程被取消前发出请求；过长的 delay(300ms)会被取消导致请求丢失 =====
        try {
            kotlinx.coroutines.delay(100)
        } catch (c: kotlinx.coroutines.CancellationException) {
            return emptySuggestion()   // 用户继续输入，本次被取消
        }

        // ===== 缓存：相同 PSI + 前缀 5 秒内复用 =====
        val cacheKey = CompletionCache.keyOf(
            (context.filePath ?: "") + ":" + (context.caretElementType ?: ""),
            context.beforeCaret ?: ""
        )
        val cached = CompletionCache.get(cacheKey)
        if (cached != null && cached.isNotBlank()) {
            LOG.info("getSuggestion: cache hit, scene=" + scene.id)
            val cachedClean = finalClean(scene, cached)
            if (cachedClean.isBlank()) return emptySuggestion()
            return InlineCompletionSuggestion.withFlow {
                emit(InlineCompletionTextElement(cachedClean, TextAttributes()))
            }
        }

        val fullText = suspendCancellableCoroutine<String?> { cont ->
            com.deepseek.plugin.status.PluginStatus.getInstance()
                .setReqState(com.deepseek.plugin.status.PluginStatus.ReqState.REQUESTING)
            val future = DeepSeekClient.getInstance().chatStream(
                DeepSeekClient.StreamRequest().apply {
                    this.apiKey = configuredKey
                    this.baseUrl = state.baseUrl
                    this.model = state.model
                    // 补全需要更高的创造性：0.2 温度下模型对"续写"任务倾向输出空/极简内容
                    this.temperature = 0.7
                    // 补全内容通常很短，限制输出长度加快响应（512→128 提速明显）
                    this.maxTokens = 128
                    this.messages = messages
                },
                object : com.deepseek.plugin.client.ChatStreamListener {
                    override fun onDelta(delta: String) { /* 一次性返回最终建议 */ }
                    override fun onFinish(fullText: String) {
                        com.deepseek.plugin.status.PluginStatus.getInstance()
                            .setReqState(com.deepseek.plugin.status.PluginStatus.ReqState.DONE)
                        cont.resume(fullText)
                    }
                    override fun onError(message: String, cause: Throwable?) {
                        com.deepseek.plugin.status.PluginStatus.getInstance()
                            .setReqState(com.deepseek.plugin.status.PluginStatus.ReqState.ERROR)
                        cont.resume(null)   // 静默失败，不打断用户
                    }
                }
            )
            cont.invokeOnCancellation { future.cancel(true) }
        } ?: return emptySuggestion()

        // 缓存结果
        if (fullText.isNotBlank()) {
            CompletionCache.put(cacheKey, fullText)
        }

        if (fullText.isBlank()) return emptySuggestion()
        if (editor.isDisposed) return emptySuggestion()
        // 用户已移动光标则放弃本次建议（读光标必须在 ReadAction 内）
        val caret = com.intellij.openapi.application.ReadAction.compute<Int, RuntimeException> {
            if (editor.isDisposed) -1 else editor.caretModel.offset
        }
        if (caret < 0) return emptySuggestion()
        if (caret != request.endOffset && caret != request.startOffset) return emptySuggestion()

        // 防御：即使模型返回了代码块标记/多余解释，也清理后再展示
        val clean = finalClean(scene, fullText)
        if (clean.isBlank()) return emptySuggestion()
        LOG.info("getSuggestion: showing completion, len=" + clean.length + " text=" + clean.replace("\n", "\\n").take(200))
        return InlineCompletionSuggestion.withFlow {
            emit(InlineCompletionTextElement(clean, TextAttributes()))
        }
    }

    /** 去掉行内补全结果中的代码块标记、前后解释与首尾空白。 */
    private fun cleanCompletionText(raw: String): String {
        var text = raw.trim()
        // 去掉 ```java ... ``` 或 ``` ... ``` 围栏
        if (text.startsWith("```")) {
            val firstNl = text.indexOf('\n')
            val fenceStart = if (firstNl < 0) text.length else firstNl + 1
            text = text.substring(fenceStart)
            val fenceEnd = text.lastIndexOf("```")
            if (fenceEnd >= 0) text = text.substring(0, fenceEnd)
            text = text.trim()
        }
        return text
    }

    /** 按场景做最终清理；import 场景额外做语法防御，防止模型输出方法/类导致语法错误。 */
    private fun finalClean(scene: com.deepseek.plugin.completion.CompletionScene, raw: String): String {
        val base = cleanCompletionText(raw)
        return if (scene == com.deepseek.plugin.completion.CompletionScene.IMPORT_SUGGESTION) {
            sanitizeImportSuggestion(base)
        } else {
            base
        }
    }

    /**
     * import 场景防御：
     * - 模型若输出方法/类/语句块（无分号或含 { 等危险结构），丢弃；
     * - 若输出带分号的内容（完整 import 或片段如 "List;"），只保留第一个分号前的内容；
     * - 若模型在 import 后拼接了方法体（如 "import x;\npublic class ..."），截断到分号。
     */
    private fun sanitizeImportSuggestion(text: String): String {
        var t = text.trim()
        val semi = t.indexOf(';')
        if (semi >= 0) {
            return t.substring(0, semi + 1).trim()
        }
        // 无分号：看起来像方法/类/语句块则丢弃；纯片段（如 "List"）保留
        val lower = t.lowercase()
        if (lower.startsWith("public") || lower.startsWith("private")
            || lower.startsWith("protected") || lower.startsWith("class ")
            || lower.startsWith("interface ") || lower.startsWith("enum ")
            || lower.startsWith("void ") || lower.startsWith("static ")
            || lower.contains("{") || lower.contains("(")) {
            return ""
        }
        return t
    }

    /** 从光标前文本提取最近的注释内容（用于 PSI 无法识别空注释的情况）。 */
    private fun extractCommentFromBefore(before: String): String {
        val idx = before.lastIndexOf("/*")
        if (idx < 0) {
            // 可能是 // 行注释
            val lineIdx = before.lastIndexOf("//")
            if (lineIdx < 0) return ""
            return before.substring(lineIdx + 2).trim()
        }
        val comment = before.substring(idx)
        // 去掉注释结束符之前的部分；保留 / 之后的内容
        val content = comment.removePrefix("/*").removeSuffix("*/")
        return content.trim()
    }

    /** 读取系统剪贴板文本；非文本内容或读取失败返回 null。 */
    private fun readClipboardText(): String? {
        return try {
            val manager = com.intellij.openapi.ide.CopyPasteManager.getInstance()
            val contents = manager.contents
            if (contents != null && contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                val text = contents.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
                text?.trim()
            } else {
                null
            }
        } catch (t: Throwable) {
            LOG.warn("readClipboard failed", t)
            null
        }
    }

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        val state = DeepSeekState.getInstance()
        // 触发事件：DocumentChange（输入字符/回车）、Backspace 后的重新输入。
        // 抑制事件：LookupChange（代码补全弹窗变化）、SuggestionInserted（已插入建议）——
        // 弹窗活跃期间触发会干扰用户，且会导致场景误判。
        val eventName = event.javaClass.simpleName
        val suppressed = eventName == "LookupChange"
                || event is com.intellij.codeInsight.inline.completion.InlineCompletionEvent.SuggestionInserted
        val result = state.completionEnabled && state.apiKey.trim().isNotEmpty() && !suppressed
        LOG.info("isEnabled: " + result + " (completionEnabled=" + state.completionEnabled
                + ", hasKey=" + state.apiKey.trim().isNotEmpty()
                + ", eventType=" + eventName + ")")
        return result
    }

    /** 无建议时返回空流（渲染为空，等价于不显示）。 */
    private fun emptySuggestion(): InlineCompletionSuggestion =
        InlineCompletionSuggestion.withFlow { }
}
