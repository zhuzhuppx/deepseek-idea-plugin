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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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

    /** 方法签名 + 方法体 判定正则（0.1.6 起提取为常量：流式增量下 onDelta 高频调用，每次 new Regex 会反复编译） */
    private val METHOD_SIGNATURE_REGEX = Regex(
        "^(?:(?:public|private|protected|static|final|synchronized|abstract|default|native|strictfp)\\s+)*" +
                "(?:[\\w<>\\[\\],.\\s]+?\\s+)?" +
                "\\w+\\s*\\([^)]*\\)\\s*(?:throws\\s+[\\w,.\\s]+)?\\s*\\{"
    )

    override val id: InlineCompletionProviderID =
        InlineCompletionProviderID("deepseek-idea-plugin")

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
        // 懒自愈：已有 Key 但状态仍显示"未配置"时自动标记已连接
        // （正常由 ConnectionAutoInitListener 在启动时设置，这里兜底热加载/首次触发场景）
        val pStatus = com.deepseek.plugin.status.PluginStatus.getInstance()
        if (pStatus.connState == com.deepseek.plugin.status.PluginStatus.ConnState.NO_KEY) {
            pStatus.setConnState(com.deepseek.plugin.status.PluginStatus.ConnState.CONNECTED)
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
        // buildScenePrompt 内部会读相关源码文件（PSI），必须在 ReadAction 内执行
        val prompt = com.intellij.openapi.application.ReadAction.compute<String, RuntimeException> {
            try {
                JavaExpertPrompt.buildScenePrompt(project, file, scene, context)
            } catch (t: Throwable) {
                LOG.warn("getSuggestion: buildScenePrompt failed", t)
                // 降级：退回不含项目上下文的纯续写提示词
                JavaExpertPrompt.completionPrompt(project, file, context.filePath,
                        context.importsSummary, context.enclosingSignature,
                        context.beforeCaret, context.afterCaret)
            }
        }

        // ===== 记录当前文档版本：请求返回后若文档已变化（用户手敲/删除/粘贴），
        // 直接丢弃过期建议，防止旧请求的幽灵文本覆盖/干扰用户刚敲的代码 =====
        val docStamp = com.intellij.openapi.application.ReadAction.compute<Long, RuntimeException> {
            if (editor.isDisposed) -1L else editor.document.modificationStamp
        }
        if (docStamp < 0) return emptySuggestion()

        // 读取剪贴板内容作为额外线索，帮助模型猜用户下一步想输入什么。
        // 只有内容"像 Java 代码"时才注入：剪贴板里常是 URL/报错信息/别的项目代码/中文文本，
        // 模型会把它们编进补全结果——"提示的不是我想要的代码"的常见来源（2026-08-14 修复）。
        val clipboardText = readClipboardText()
        val finalPrompt = if (clipboardText != null && isJavaLikeClipboard(clipboardText)) {
            prompt + "\n\n【剪贴板内容（仅供参考：若与当前补全位置无关请完全忽略，严禁把它原样编入补全结果）】\n" +
                    clipboardText.take(400)
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
            // 缓存命中同样校验文档版本：用户期间改过代码则不用过期缓存
            val hitStamp = com.intellij.openapi.application.ReadAction.compute<Long, RuntimeException> {
                if (editor.isDisposed) -1L else editor.document.modificationStamp
            }
            if (hitStamp != docStamp) return emptySuggestion()
            val cachedClean = finalClean(scene, context.beforeCaret ?: "", trimExistingPrefix(context.beforeCaret ?: "", cached))
            if (cachedClean.isBlank()) return emptySuggestion()
            return InlineCompletionSuggestion.withFlow {
                emit(InlineCompletionTextElement(cachedClean, TextAttributes()))
            }
        }

        // ===== 请求（流式增量显示）=====
        // 说明：onDelta 每到一个增量，就对【累积文本】跑完整防御清理（finalClean），
        // 通过"前缀一致性 + 起始稳定观察窗"后只把新增部分 emit 进 withFlow，
        // 幽灵文本随生成持续刷新（不用等全文），onFinish 再补发最终清理后的剩余部分。
        // 每个 delta 都校验文档 stamp：用户手敲/删改立即中断本次补全，绝不覆盖手敲代码。
        // ===== 推理模式（自动，无需用户配置）=====
        // 生成类场景（注释转代码/样板/测试/方法实现/异常/成员声明/JSON）模型要"从零写一段代码"，
        // 自动开低强度推理，显著减少凭空乱猜（"提示的不是我想要的代码"）；片段类场景
        // （注解/import/链式/续写等）输出很短，开推理只会拖慢首字符，保持关闭。
        // 想更强可把下面的 "low" 改成 "medium"/"high"。
        val reasoningScenes = setOf(
            com.deepseek.plugin.completion.CompletionScene.COMMENT_TO_CODE,
            com.deepseek.plugin.completion.CompletionScene.BOILERPLATE,
            com.deepseek.plugin.completion.CompletionScene.IMPLEMENT_METHOD,
            com.deepseek.plugin.completion.CompletionScene.TEST_SKELETON,
            com.deepseek.plugin.completion.CompletionScene.EXCEPTION_HANDLING,
            com.deepseek.plugin.completion.CompletionScene.MEMBER_DECLARATION,
            com.deepseek.plugin.completion.CompletionScene.JSON_XML
        )
        val reasoningEffort = if (scene in reasoningScenes) "low" else "none"
        // 开启推理后模型先"思考"再输出，TTFT 明显变长，超时放宽到 30s；
        // 且推理 token 可能计入 max_tokens 预算，生成场景的额度同步加大。
        val requestTimeoutMs = if (reasoningEffort != "none") 30_000L else 15_000L
        val channel = Channel<InlineCompletionTextElement>(Channel.UNLIMITED)
        val reqJob = SupervisorJob()
        val reqScope = CoroutineScope(reqJob + Dispatchers.IO)
        val rawBuf = StringBuilder()
        val emittedBuf = StringBuilder()
        var frozen = false
        var future: java.util.concurrent.CompletableFuture<String>? = null

        reqScope.launch {
            try {
                val done = kotlinx.coroutines.withTimeoutOrNull(requestTimeoutMs) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        com.deepseek.plugin.status.PluginStatus.getInstance()
                            .setReqState(com.deepseek.plugin.status.PluginStatus.ReqState.REQUESTING)
                        future = DeepSeekClient.getInstance().chatStream(
                            DeepSeekClient.StreamRequest().apply {
                                this.apiKey = configuredKey
                                this.baseUrl = state.baseUrl
                                this.model = state.model
                                // 温度按场景区分：精确小片段补全（注解/字段/import/键值/链式调用等）
                                // 用低温 0.3 减少发散；生成类场景（注释转代码/样板/测试/异常）用 0.5。
                                // 固定 0.7 对行内补全太高，模型容易输出与上下文无关的碎片（如 .println(...)）。
                                this.temperature = when (scene) {
                                    com.deepseek.plugin.completion.CompletionScene.ANNOTATION,
                                    com.deepseek.plugin.completion.CompletionScene.MEMBER_DECLARATION,
                                    com.deepseek.plugin.completion.CompletionScene.IMPORT_SUGGESTION,
                                    com.deepseek.plugin.completion.CompletionScene.CONFIG_KEY,
                                    com.deepseek.plugin.completion.CompletionScene.I18N_KEY,
                                    com.deepseek.plugin.completion.CompletionScene.REGEX_BUILD,
                                    com.deepseek.plugin.completion.CompletionScene.JSON_XML,
                                    com.deepseek.plugin.completion.CompletionScene.CHAIN_CALL_PREDICTION,
                                    com.deepseek.plugin.completion.CompletionScene.FRAMEWORK_API,
                                    com.deepseek.plugin.completion.CompletionScene.SQL_REPOSITORY,
                                    com.deepseek.plugin.completion.CompletionScene.EXCEPTION_HANDLING,
                                    // 普通续写也用 0.3：0.5 在"上文有重复模式"时容易发散出
                                    // 无意义重复行（如 event; 三连），低温显著降低这种幻觉
                                    com.deepseek.plugin.completion.CompletionScene.CODE_CONTINUATION -> 0.3
                                    else -> 0.5
                                }
                                // 单行片段场景 256 token 足够（println 长字符串等），
                                // 配合 ensureSyntaxComplete 自动闭合兜底，即使被截断也会补上字符串/括号/分号。
                                // 多行生成类场景给 512；其中开启推理的场景给 1024：
                                // 推理 token 可能计入 max_tokens 预算，256/512 容易截断在方法体中间，
                                // 弹出缺右括号的残缺代码（"提示的不是我想要的代码"）。
                                this.maxTokens = if (reasoningEffort != "none") {
                                    1024
                                } else when (scene) {
                                    com.deepseek.plugin.completion.CompletionScene.COMMENT_TO_CODE,
                                    com.deepseek.plugin.completion.CompletionScene.BOILERPLATE,
                                    com.deepseek.plugin.completion.CompletionScene.IMPLEMENT_METHOD,
                                    com.deepseek.plugin.completion.CompletionScene.TEST_SKELETON,
                                    com.deepseek.plugin.completion.CompletionScene.EXCEPTION_HANDLING,
                                    com.deepseek.plugin.completion.CompletionScene.MEMBER_DECLARATION,
                                    com.deepseek.plugin.completion.CompletionScene.JSON_XML -> 512
                                    else -> 256
                                }
                                // stop tokens：单行场景（注解/import/键值/正则/链式/配置）输出一行即停，
                                // 避免模型继续拖沓输出多行无关内容拖慢补全；多行场景（生成方法体/测试/样板）
                                // 不设 stop，让模型自由输出多行。
                                this.stop = if (scene == com.deepseek.plugin.completion.CompletionScene.ANNOTATION
                                    || scene == com.deepseek.plugin.completion.CompletionScene.IMPORT_SUGGESTION
                                    || scene == com.deepseek.plugin.completion.CompletionScene.CONFIG_KEY
                                    || scene == com.deepseek.plugin.completion.CompletionScene.I18N_KEY
                                    || scene == com.deepseek.plugin.completion.CompletionScene.REGEX_BUILD
                                    || scene == com.deepseek.plugin.completion.CompletionScene.CHAIN_CALL_PREDICTION
                                ) {
                                    listOf("\n")
                                } else {
                                    null
                                }
                                this.messages = messages
                                // 推理强度：按场景自动决定（生成类 low / 片段类 none）
                                this.reasoningEffort = reasoningEffort
                            },
                            object : com.deepseek.plugin.client.ChatStreamListener {
                                override fun onDelta(delta: String) {
                                    rawBuf.append(delta)
                                    if (frozen) return
                                    // 用户手敲/删改 → 立即中断本次补全（轻量读 modificationStamp，SSE 线程安全）
                                    if (docStampChanged(editor, docStamp)) {
                                        future?.cancel(true)
                                        return
                                    }
                                    try {
                                        val raw = rawBuf.toString()
                                        val before = context.beforeCaret ?: ""
                                        val probe = finalClean(scene, before, trimExistingPrefix(before, raw))
                                        if (probe.isEmpty()) return                       // 防御丢弃中，继续累积等待
                                        if (!probe.startsWith(emittedBuf.toString())) {    // 前缀被防御规则推翻 → 冻结，不再增量
                                            frozen = true
                                            return
                                        }
                                        if (!isStreamingStartStable(probe, scene)) return  // 观察窗：开头尚未定型
                                        val incr = probe.removePrefix(emittedBuf.toString())
                                        if (incr.isEmpty()) return
                                        emittedBuf.append(incr)
                                        channel.trySend(InlineCompletionTextElement(incr, TextAttributes()))
                                    } catch (t: Throwable) {
                                        LOG.warn("streaming onDelta failed", t)
                                    }
                                }

                                override fun onFinish(fullText: String) {
                                    com.deepseek.plugin.status.PluginStatus.getInstance()
                                        .setReqState(com.deepseek.plugin.status.PluginStatus.ReqState.DONE)
                                    try {
                                        // 缓存完整原文（后续同位置请求可秒出）
                                        if (fullText.isNotBlank()) {
                                            CompletionCache.put(cacheKey, fullText)
                                        }
                                        // 展示前校验（必须在 ReadAction 内读取）：
                                        // 1) 用户已移动光标（位置与请求时不一致）→ 放弃；
                                        // 2) 文档已被修改（用户手敲/删除/粘贴）→ 放弃，防止过期建议覆盖手敲代码。
                                        val ok = com.intellij.openapi.application.ReadAction.compute<Boolean, RuntimeException> {
                                            if (editor.isDisposed) {
                                                false
                                            } else {
                                                val caret = editor.caretModel.offset
                                                (caret == request.endOffset || caret == request.startOffset) &&
                                                    editor.document.modificationStamp == docStamp
                                            }
                                        }
                                        if (ok && fullText.isNotBlank()) {
                                            val clean = finalClean(
                                                scene,
                                                context.beforeCaret ?: "",
                                                trimExistingPrefix(context.beforeCaret ?: "", fullText)
                                            )
                                            if (clean.isNotEmpty()) {
                                                if (frozen || !clean.startsWith(emittedBuf.toString())) {
                                                    // 流式中途被防御冻结、或最终清理与已 emit 前缀不一致：
                                                    // 已 emit 的幽灵文本无法回退，不再追加；
                                                    // 若什么都没 emit 过，则一次性给出最终清理结果。
                                                    if (emittedBuf.isEmpty()) {
                                                        channel.trySend(InlineCompletionTextElement(clean, TextAttributes()))
                                                    }
                                                } else {
                                                    val rest = clean.removePrefix(emittedBuf.toString())
                                                    if (rest.isNotEmpty()) {
                                                        channel.trySend(InlineCompletionTextElement(rest, TextAttributes()))
                                                    }
                                                }
                                            }
                                        }
                                    } catch (t: Throwable) {
                                        LOG.warn("streaming onFinish failed", t)
                                    }
                                    channel.close()
                                    cont.resume(Unit)
                                }

                                override fun onError(message: String, cause: Throwable?) {
                                    // 取消（用户继续输入/超时中断）不算错误：状态恢复空闲，不显示"请求失败"
                                    if (future?.isCancelled == true) {
                                        com.deepseek.plugin.status.PluginStatus.getInstance()
                                            .setReqState(com.deepseek.plugin.status.PluginStatus.ReqState.IDLE)
                                    } else {
                                        com.deepseek.plugin.status.PluginStatus.getInstance()
                                            .setReqState(com.deepseek.plugin.status.PluginStatus.ReqState.ERROR)
                                    }
                                    channel.close()
                                    cont.resume(Unit)
                                }
                            }
                        )
                        cont.invokeOnCancellation {
                            future?.cancel(true)
                            channel.close()
                        }
                    }
                }
                if (done == null) {
                    // 超时 / 网络失败 / 被新输入取消：静默放弃。
                    // 若状态仍卡在 REQUESTING（如超时瞬间回调未跑完），恢复 IDLE。
                    if (com.deepseek.plugin.status.PluginStatus.getInstance().reqState
                        == com.deepseek.plugin.status.PluginStatus.ReqState.REQUESTING
                    ) {
                        com.deepseek.plugin.status.PluginStatus.getInstance()
                            .setReqState(com.deepseek.plugin.status.PluginStatus.ReqState.IDLE)
                    }
                }
            } finally {
                channel.close()
            }
        }

        return InlineCompletionSuggestion.withFlow {
            try {
                for (el in channel) emit(el)
            } finally {
                reqJob.cancel()
            }
        }
    }

    /**
     * 行内补全前缀修剪（"重写"防御）。
     *
     * 模型拿到【光标前代码】后经常把已有代码原样重复输出一段再续写，直接插入就会
     * 从光标前很远的地方开始覆盖用户已有代码——表现成"重写"而不是"补全"。
     * 这里找出候选文本开头与光标前文本中某段连续行完全相同的部分并裁剪掉，
     * 只保留光标位置之后真正新增的内容。
     *
     * 行比较时忽略首尾空白（模型可能重排缩进）。
     */
    private fun trimExistingPrefix(beforeCaret: String, candidate: String): String {
        if (candidate.isBlank() || beforeCaret.isBlank()) return candidate
        val beforeLines = beforeCaret.split("\n").map { it.trim() }
        val candLines = candidate.split("\n")
        val first = candLines.firstOrNull()?.trim() ?: return candidate
        if (first.isEmpty()) return candidate
        // 候选第一行必须在光标前文本中出现过，才可能存在前缀重复
        val startIdx = beforeLines.indices.filter { beforeLines[it] == first }
        if (startIdx.isEmpty()) return candidate
        var best = 0
        for (i in startIdx) {
            var len = 0
            while (len < candLines.size && i + len < beforeLines.size
                && beforeLines[i + len] == candLines[len].trim()
            ) {
                len++
            }
            if (len > best) best = len
        }
        // 无匹配或匹配不足一行：不裁剪
        if (best <= 0) return candidate
        // 整个候选都是已有内容（无新增）→ 返回空，不显示建议
        if (best >= candLines.size) return ""
        return candLines.drop(best).joinToString("\n")
    }

    /** 流式阶段轻量检测文档是否被用户改动（SSE 线程调用，读 modificationStamp 线程安全）。 */
    private fun docStampChanged(editor: com.intellij.openapi.editor.Editor, docStamp: Long): Boolean {
        if (editor.isDisposed) return true
        return try {
            editor.document.modificationStamp != docStamp
        } catch (t: Throwable) {
            true
        }
    }

    /**
     * 流式"观察窗"：防御规则依赖开头 token 判定（方法签名、badPrefixes、字段特征等），
     * 若在开头定型前就把半截内容 emit 出去，后续防御可能把它整体推翻（probe 变空），
     * 而已 emit 的幽灵文本无法回退 → 残留。因此在开头结构稳定前不 emit：
     * - 出现换行/分号/左大括号/等号 → 开头已定型（语句、字段、方法体等边界已出现）；
     * - 长度 ≥ 12 且不是"修饰符开头的方法签名嫌疑"（如 private String foo(...) 半截）→ 稳定。
     */
    private fun isStreamingStartStable(probe: String, scene: com.deepseek.plugin.completion.CompletionScene): Boolean {
        if (probe.contains('\n') || probe.contains(';') || probe.contains('{') || probe.contains('=')) return true
        if (probe.length >= 12) {
            // 方法签名半截（修饰符+类型+方法名+左括号，还没见到 { 或 ;）：等 {/;/换行/= 出现，
            // 避免 emit 后被 isMethodSignatureWithBody 整体判空导致残留。
            if (probe.contains('(') && isModifierLed(probe)) {
                return false
            }
            return true
        }
        return false
    }

    private fun isModifierLed(text: String): Boolean {
        val s = text.trimStart()
        return s.startsWith("private") || s.startsWith("public") || s.startsWith("protected")
            || s.startsWith("static") || s.startsWith("final") || s.startsWith("synchronized")
            || s.startsWith("abstract") || s.startsWith("default") || s.startsWith("native")
    }

    /** 去掉行内补全结果中的代码块标记、前后解释与首尾空白。 */
    private fun cleanCompletionText(raw: String): String {        var text = raw.trim()
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
    private fun finalClean(scene: com.deepseek.plugin.completion.CompletionScene, beforeCaret: String, raw: String): String {
        val base = cleanCompletionText(raw)
        // 公共防御（所有场景）：重复行垃圾检测，如 "event;\nevent;\nevent;" 三连。
        // 之前只加在 CODE_CONTINUATION，漏了 TEST_SKELETON（空 @Test 方法体）等生成类场景——
        // 模型看到上文有连续同类调用（publishEvent）后机械模仿，空方法体+骨架提示词更容易发散。
        // 行内补全输出相邻完全相同行在任何场景都没有合理语义（getter/setter、given/when/then 不会相同），丢弃。
        if (hasDuplicateLines(base)) return ""
        val cleaned = when (scene) {
            com.deepseek.plugin.completion.CompletionScene.IMPORT_SUGGESTION -> sanitizeImportSuggestion(base)
            com.deepseek.plugin.completion.CompletionScene.ANNOTATION -> sanitizeAnnotationSuggestion(beforeCaret, base)
            com.deepseek.plugin.completion.CompletionScene.MEMBER_DECLARATION -> sanitizeMemberDeclarationSuggestion(base)
            // 生成类场景：本就要输出方法（Getter/Setter、@Override 实现、@Test 方法、注释转代码、try-catch），
            // 不能套用"方法签名丢弃"，只做 } 开头与文件级防御。
            com.deepseek.plugin.completion.CompletionScene.BOILERPLATE,
            com.deepseek.plugin.completion.CompletionScene.IMPLEMENT_METHOD,
            com.deepseek.plugin.completion.CompletionScene.TEST_SKELETON,
            com.deepseek.plugin.completion.CompletionScene.COMMENT_TO_CODE,
            com.deepseek.plugin.completion.CompletionScene.EXCEPTION_HANDLING -> sanitizeGeneratedCode(base)
            else -> sanitizeCodeContinuation(beforeCaret, base)
        }
        // 兜底：语法完整性自动闭合。模型被 max_tokens 截断时输出半截（字符串/括号/分号缺失），
        // 这里补全闭合符，避免出现 println("xxx 这种无法编译的残缺建议。
        return ensureSyntaxComplete(scene, cleaned)
    }

    /**
     * 语法完整性兜底（针对模型输出被截断）：
     * 1) 未闭合的双引号 → 补 "；
     * 2) 未闭合的小括号 → 补 )；
     * 3) 语句类续写场景（CODE_CONTINUATION/FRAMEWORK_API/SQL_REPOSITORY）以非中间态结尾且缺分号 → 补 ;。
     * 生成类场景不补分号（方法体/块以 { 结尾是正常的）；片段类场景（REGEX/CONFIG/I18N/CHAIN）不补分号。
     */
    private fun ensureSyntaxComplete(scene: com.deepseek.plugin.completion.CompletionScene, text: String): String {
        var t = text.trim()
        if (t.isEmpty()) return t
        // 1) 引号闭合：统计未转义的 "，奇数则补一个
        var quotes = 0
        var i = 0
        while (i < t.length) {
            if (t[i] == '\\') {
                i += 2
                continue
            }
            if (t[i] == '"') quotes++
            i++
        }
        var fixedQuote = false
        if (quotes % 2 == 1) {
            t += "\""
            fixedQuote = true
        }
        // 2) 小括号闭合（截断场景多为语句尾，粗统计足够）
        val open = t.count { it == '(' }
        val close = t.count { it == ')' }
        var fixedParen = false
        if (open > close) {
            repeat(open - close) { t += ")" }
            fixedParen = true
        }
        // 3) 语句类场景补分号
        val statementScenes = setOf(
            com.deepseek.plugin.completion.CompletionScene.CODE_CONTINUATION,
            com.deepseek.plugin.completion.CompletionScene.FRAMEWORK_API,
            com.deepseek.plugin.completion.CompletionScene.SQL_REPOSITORY
        )
        if (scene in statementScenes && !t.endsWith(";")) {
            val last = t.last()
            val middle = last == '{' || last == '}' || last == ',' || last == '.' || last == '('
                    || last == '+' || last == '-' || last == '*' || last == '/' || last == '='
                    || last == ':' || last == '|' || last == '&' || last == '>' || last == '<'
            if (!middle) {
                t += ";"
            }
        }
        return t
    }

    /** 注解场景防御：只保留单个注解，丢弃方法/字段/语句等非注解内容。 */
    private fun sanitizeAnnotationSuggestion(beforeCaret: String, text: String): String {
        var t = text.trim()
        if (t.isEmpty()) return t
        // 光标前当前行已输入半截注解（如 @Aut）→ 去掉模型输出开头的 @，避免 "@Aut@Autowired"
        val beforeLine = beforeCaret.substringAfterLast('\n').trim()
        val hasAtPrefix = beforeLine.startsWith("@")
        if (hasAtPrefix) {
            while (t.startsWith("@")) t = t.substring(1).trim()
        }
        if (t.isEmpty()) return ""
        // 非法开头：模型输出的是方法/类/语句（去 @ 后以修饰符或关键字开头）→ 丢弃
        val lower = t.lowercase()
        val badPrefixes = listOf(
            "public", "private", "protected", "void", "static",
            "class", "interface", "enum", "record", "return", "if", "for", "while"
        )
        if (badPrefixes.any { lower.startsWith(it) }) {
            return ""
        }
        if (!hasAtPrefix && !t.startsWith("@")) {
            t = "@" + t
        }
        // 只保留第一个注解：截断到换行（注解一般在一行内；@Value("${...}") 不会被误截）
        val nl = t.indexOf('\n')
        if (nl >= 0) t = t.substring(0, nl).trim()
        return t
    }

    /** 成员声明场景防御：只保留"注解+字段声明"，丢弃方法/语句块/表达式碎片。 */
    private fun sanitizeMemberDeclarationSuggestion(text: String): String {
        var t = text.trim()
        if (t.isEmpty()) return t
        // 开头必须是字段特征：注解(@)、修饰符、或大写字母开头的类型名（如 String name;）。
        // 否则（.println / } else / if( / 中文碎片 / 表达式续写）一律丢弃。
        val lower = t.lowercase()
        val startsWithField = t.startsWith("@")
            || lower.startsWith("private ") || lower.startsWith("public ")
            || lower.startsWith("protected ") || lower.startsWith("static ")
            || lower.startsWith("final ") || lower.startsWith("volatile ")
            || lower.startsWith("transient ")
            || (t[0].isUpperCase() && !lower.startsWith("if ") && !lower.startsWith("for "))
        if (!startsWithField) {
            return ""
        }
        val semi = t.indexOf(';')
        val brace = t.indexOf('{')
        // 左大括号出现在分号之前（或无分号）→ 方法体/语句块，不是字段声明，丢弃
        if (brace >= 0 && (semi < 0 || brace < semi)) {
            return ""
        }
        // 方法签名（void 开头）→ 丢弃
        if (t.startsWith("void ") || t.startsWith("public void ")
            || t.startsWith("private void ") || t.startsWith("protected void ")
            || t.startsWith("public static void ") || t.startsWith("private static void ")
        ) {
            return ""
        }
        // 只保留到第一个分号（字段声明结束），防模型多输出后续内容
        if (semi >= 0) {
            t = t.substring(0, semi + 1).trim()
        }
        return t
    }

    /**
     * 行内补全通用防御：行内补全永远不该输出文件级结构（package / import / class 声明）。
     * 模型若在方法体内输出了完整文件（如误判测试场景时输出整个测试类），
     * - 以 package 开头 → 整个丢弃；
     * - 中间出现文件级结构 → 截断到该结构之前，保留前面的语句。
     */
    private fun sanitizeFileLevelStructure(text: String): String {
        var t = text.trim()
        if (t.isEmpty()) return t
        // 以文件级声明开头：直接丢弃
        if (startsWithFileLevel(t)) return ""
        // 在中间出现：截断到该结构前
        val nl = "\n"
        val lines = t.split(nl)
        val sb = StringBuilder()
        for (line in lines) {
            if (startsWithFileLevel(line.trim())) {
                break
            }
            if (sb.isNotEmpty()) sb.append(nl)
            sb.append(line)
        }
        return sb.toString().trim()
    }

    private fun startsWithFileLevel(s: String): Boolean {
        return s.startsWith("package ")
                || s.startsWith("import ")
                || s.startsWith("public class ")
                || s.startsWith("class ")
                || s.startsWith("public interface ")
                || s.startsWith("interface ")
                || s.startsWith("public enum ")
                || s.startsWith("enum ")
                || s.startsWith("public abstract class ")
                || s.startsWith("public record ")
                || s.startsWith("record ")
    }

    /**
     * 生成类场景防御：允许输出方法（getter/setter、@Override 实现、@Test 方法、try-catch），
     * 但仍丢弃：
     * 1) 以 } 开头（重复闭合括号，IDE 自动闭合已处理）；
     * 2) 文件级结构（package/import/class 声明）。
     */
    private fun sanitizeGeneratedCode(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return t
        if (t.startsWith("}")) {
            return ""
        }
        return sanitizeFileLevelStructure(t)
    }

    /**
     * 默认续写场景（CODE_CONTINUATION 等）防御，针对用户实测的几种垃圾输出：
     * 1) 输出以 } 开头 → 模型想补"闭合大括号 + 后续内容"（如方法体末尾补 getFileType 方法）。
     *    IDE 自动闭合已处理大括号，这种建议在方法体内几乎总是多余/出错（大括号数量错位 →
     *    方法被套进方法里），直接丢弃；
     * 2) 输出是"方法签名 + 方法体"（private String getFileType(File file) { ... }）→
     *    默认续写场景（尤其光标在方法体内时）不该输出方法定义，否则产生非法嵌套方法，丢弃；
     * 3) 仍叠加上文件级结构防御。
     * 注：重复行垃圾（event; 三连）已在 finalClean 公共层检测，所有场景统一处理。
     */
    private fun sanitizeCodeContinuation(beforeCaret: String, text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return t
        // 1) 闭合括号开头：丢弃。注：光标在 if 块 } 后需要补 else 时，模型应输出 " else {"（不带 }），
        //    带 } 的输出意味着重复了光标前已有的括号，丢弃是安全的。
        if (t.startsWith("}")) {
            return ""
        }
        // 2) 方法签名 + 方法体：丢弃（方法体内不允许嵌套方法）
        if (isMethodSignatureWithBody(t)) {
            return ""
        }
        // 3) 文件级结构防御
        return sanitizeFileLevelStructure(t)
    }

    /**
     * 重复行垃圾检测：候选文本拆行后（忽略空行与首尾空白），
     * 存在相邻两行内容完全相同 → 无意义重复（如 "event;" 三连），丢弃。
     * 行内补全输出多行相同语句没有任何合理场景（循环体/展开不会在行内补全出现）。
     */
    private fun hasDuplicateLines(text: String): Boolean {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return false
        for (i in 1 until lines.size) {
            if (lines[i] == lines[i - 1]) return true
        }
        return false
    }

    /** 判断文本是否以"方法签名 + 方法体"开头（排除 if/for/while/switch/catch/try 等控制块）。 */
    private fun isMethodSignatureWithBody(text: String): Boolean {
        val t = text.trim()
        if (!t.contains("{") || !t.contains("(")) return false
        val lower = t.lowercase()
        if (lower.startsWith("if ") || lower.startsWith("for ") || lower.startsWith("while ")
            || lower.startsWith("switch") || lower.startsWith("catch") || lower.startsWith("try ")
            || lower.startsWith("synchronized") || lower.startsWith("do ") || lower.startsWith("else")
        ) {
            return false
        }
        // 修饰符(可选多个) + 返回类型 + 方法名 + (参数列表) + [throws ...] + {
        // 如 "private String getFileType(File file) {"；"foo(bar) {" 因方法名后无空格+无修饰符不匹配。
        return METHOD_SIGNATURE_REGEX.matches(t)
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

    /** 剪贴板只有"像 Java 代码"时才作为参考注入补全提示词。
     *  否则 URL/报错/中文文本等无关内容会被模型编进补全结果。 */
    private fun isJavaLikeClipboard(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 1200) return false
        // 有代码特征（花括号/分号）直接判定
        if (t.contains('{') || t.contains(';')) return true
        // 否则看首行是否以 Java 语法开头
        val first = t.split("\n").firstOrNull()?.trim() ?: return false
        val javaStarts = listOf(
            "public ", "private ", "protected ", "static ", "final ", "synchronized ",
            "class ", "interface ", "enum ", "record ", "import ", "package ",
            "return ", "if ", "for ", "while ", "switch ", "try ", "new ", "@",
            "String ", "int ", "long ", "double ", "float ", "boolean ", "char ",
            "byte ", "short ", "void ", "var ", "List", "Map", "Set", "Optional"
        )
        return javaStarts.any { first.startsWith(it) }
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
