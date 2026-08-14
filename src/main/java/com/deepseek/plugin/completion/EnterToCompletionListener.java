package com.deepseek.plugin.completion;

import com.intellij.codeInsight.inline.completion.action.CallInlineCompletionAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.project.Project;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 回车后自动触发行内补全。
 *
 * IDEA 行内补全在"接受建议后回车"时不派发 DocumentChange 事件，
 * 导致无法自动续写。本监听器检测到回车（\n 插入）后，
 * 延迟触发手动执行 CallInlineCompletionAction 触发下一次补全。
 *
 * 防循环设计（2026-08-14 修复）：
 * 同一个 Document 可能被多个 editor 共享（分屏/重建），而 editorCreated 会为每个
 * editor 注册一个 EnterListener。旧实现把 inserting 标记放在实例字段上，导致
 * A 实例插入时 B 实例看不到标记，B 收到 documentChanged 后再次触发请求 → 无限循环
 * 烧 API。修复：插入标记/冷却时间/in-flight 锁全部改为静态全局（按 document 维度），
 * 任何实例都能看到"正在由插件写入"，从而跳过。
 */
public class EnterToCompletionListener implements EditorFactoryListener {

    private static final Logger LOG = Logger.getInstance(EnterToCompletionListener.class);

    /** 正在由插件自动插入（按 document 维度，所有实例共享）。 */
    private static final ConcurrentHashMap<com.intellij.openapi.editor.Document, AtomicBoolean> INSERTING_MAP =
            new ConcurrentHashMap<>();
    /** 最近一次自动插入的时间戳（按 document 维度），用于冷却。 */
    private static final ConcurrentHashMap<com.intellij.openapi.editor.Document, AtomicLong> LAST_INSERT_MS =
            new ConcurrentHashMap<>();
    /** 是否有注释请求正在飞行（全局），防止并发堆积。 */
    private static final AtomicBoolean REQUEST_IN_FLIGHT = new AtomicBoolean(false);

    /** 自动插入后的冷却时间：这段时间内任何注释触发都被忽略。 */
    private static final long COOLDOWN_MS = 1500L;

    @Override
    public void editorCreated(EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        Project project = editor.getProject();
        if (project == null || project.isDisposed()) {
            return;
        }
        EnterListener listener = new EnterListener(editor, project);
        editor.getDocument().addDocumentListener(listener);
        // 记录监听器，editorReleased 时移除，避免同一 document 挂多个实例互相触发
        listener.attach();
    }

    @Override
    public void editorReleased(EditorFactoryEvent event) {
        EnterListener.detach(event.getEditor());
    }

    private static class EnterListener implements DocumentListener {

        /** 静态持有的监听器引用（key = document），用于 editorReleased 时移除。 */
        private static final ConcurrentHashMap<com.intellij.openapi.editor.Document, EnterListener> ATTACHED =
                new ConcurrentHashMap<>();

        private final Editor editor;
        private final Project project;
        private final com.intellij.openapi.editor.Document document;

        EnterListener(Editor editor, Project project) {
            this.editor = editor;
            this.project = project;
            this.document = editor.getDocument();
        }

        /** 注册到 ATTACHED 表，供 editorReleased 移除。 */
        void attach() {
            ATTACHED.put(document, this);
        }

        static void detach(Editor editor) {
            com.intellij.openapi.editor.Document doc = editor.getDocument();
            EnterListener l = ATTACHED.remove(doc);
            if (l != null) {
                try {
                    doc.removeDocumentListener(l);
                } catch (Throwable ignore) {
                }
            }
        }

        /** 清洗补全建议：只取第一行、限制长度，避免插入长注释/多行。 */
        private String cleanSuggestion(String raw) {
            String text = raw.trim();
            if (text.isEmpty()) {
                return "";
            }
            // 只取第一行
            int nl = text.indexOf('\n');
            if (nl >= 0) {
                text = text.substring(0, nl);
            }
            // 去掉行首可能出现的 *（块注释续行）或 // 
            text = text.replaceAll("^\\s*\\*\\s*", "").replaceAll("^//\\s*", "");
            // 限制长度（约 30 个汉字以内）
            if (text.length() > 60) {
                text = text.substring(0, 60);
            }
            return text.trim();
        }

        @Override
        public void documentChanged(DocumentEvent event) {
            // 插件自己插入的内容，跳过（静态共享标记，任何实例都能看到）
            AtomicBoolean inserting = INSERTING_MAP.get(document);
            if (inserting != null && inserting.get()) {
                return;
            }
            // 冷却期内跳过：自动插入后短时间内不响应任何注释触发
            AtomicLong last = LAST_INSERT_MS.get(document);
            if (last != null && System.currentTimeMillis() - last.get() < COOLDOWN_MS) {
                return;
            }
            if (project.isDisposed() || editor.isDisposed()) {
                return;
            }
            // 检测触发条件（基于文档实际内容，不只依赖 newFragment）：
            // 1. 回车换行
            // 2. 光标前本行包含 // 且 // 后是行尾或空格（行注释）
            // 3. 光标前有未闭合的 /*（块注释开始）
            CharSequence newFragment = event.getNewFragment();
            boolean hasNewline = newFragment != null && indexOfNewline(newFragment) >= 0;
            boolean lineComment = isLineCommentTriggerReady();
            boolean blockComment = isBlockCommentTriggerReady();
            if (!hasNewline && !lineComment && !blockComment) {
                return;
            }
            LOG.info("enter-listener: change detected (newline=" + hasNewline
                    + ", lineComment=" + lineComment + ", blockComment=" + blockComment + "), scheduling trigger");
            final boolean isComment = lineComment || blockComment;
            // 延迟 80ms 触发（原来 400ms 太慢），让 IDE 消化事件
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    Thread.sleep(80);
                } catch (InterruptedException e) {
                    return;
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed() || editor.isDisposed()) {
                        return;
                    }
                    // 延迟后再查一次插入标记/冷却，防止 80ms 内插件已写入
                    if (inserting != null && inserting.get()) {
                        return;
                    }
                    if (last != null && System.currentTimeMillis() - last.get() < COOLDOWN_MS) {
                        return;
                    }
                    if (isComment) {
                        // 注释被 IDE 代码补全弹窗抑制 inline completion：
                        // 走独立路径：请求注释补全并自动插入（最可靠）
                        try {
                            com.intellij.codeInsight.lookup.LookupManager.getInstance(project)
                                    .hideActiveLookup();
                        } catch (Throwable ignore) {
                        }
                        requestCommentCompletion();
                    } else {
                        triggerCompletion();
                    }
                });
            });
        }

        /** 判断光标前是否满足块注释触发条件：有 /* 且尚未闭合（光标在块注释内）。 */
        private boolean isBlockCommentTriggerReady() {
            try {
                int offset = editor.getCaretModel().getOffset();
                String before = com.intellij.openapi.application.ReadAction.compute(() ->
                        document.getText(new com.intellij.openapi.util.TextRange(0, offset)));
                int open = before.lastIndexOf("/*");
                if (open < 0) {
                    return false;
                }
                String after = before.substring(open + 2);
                // 未闭合（后面没有 */）才触发
                return !after.contains("*/");
            } catch (Throwable t) {
                return false;
            }
        }

        /** 判断光标前当前行是否满足行注释触发条件：包含 // 且 // 后是行尾或空格。 */
        private boolean isLineCommentTriggerReady() {
            try {
                int offset = editor.getCaretModel().getOffset();
                int lineStart = document.getLineStartOffset(document.getLineNumber(offset));
                String lineText = document.getText(
                        new com.intellij.openapi.util.TextRange(lineStart, offset));
                int idx = lineText.indexOf("//");
                if (idx < 0) {
                    return false;
                }
                String after = lineText.substring(idx + 2);
                // // 后是行尾或只有空格才触发（避免输入 // 内容时反复触发）
                return after.isBlank();
            } catch (Throwable t) {
                return false;
            }
        }

        /** 触发补全：走 InlineCompletion 手动调用（回车续写场景）。 */
        private void triggerCompletion() {
            try {
                LOG.info("enter-listener: executing CallInlineCompletionAction");
                CallInlineCompletionAction action = new CallInlineCompletionAction();
                java.util.Map<String, Object> dataMap = new java.util.HashMap<>();
                dataMap.put(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR.getName(), editor);
                dataMap.put(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.getName(), project);
                com.intellij.openapi.actionSystem.DataContext dataContext =
                        com.intellij.openapi.actionSystem.impl.SimpleDataContext.getSimpleContext(dataMap, null);
                com.intellij.openapi.actionSystem.AnActionEvent event =
                        com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
                                "deepseek.enterCompletion", null, dataContext);
                action.actionPerformed(event);
                LOG.info("enter-listener: CallInlineCompletionAction executed");
            } catch (Throwable t) {
                LOG.warn("enter-triggered CallInlineCompletionAction failed", t);
                // fallback：直接请求注释补全并用 HintManager 展示
                requestCommentCompletion();
            }
        }

        /** 独立路径：请求注释补全并自动插入（不依赖 InlineCompletion 框架）。 */
        private void requestCommentCompletion() {
            try {
                // 全局 in-flight 锁：同一时刻只允许一个注释请求，防止并发堆积
                if (!REQUEST_IN_FLIGHT.compareAndSet(false, true)) {
                    LOG.info("enter-listener: comment request already in flight, skip");
                    return;
                }
                int offset = editor.getCaretModel().getOffset();
                String before = com.intellij.openapi.application.ReadAction.compute(() ->
                        document.getText(new com.intellij.openapi.util.TextRange(0, offset)));
                String after = com.intellij.openapi.application.ReadAction.compute(() ->
                        document.getText(new com.intellij.openapi.util.TextRange(offset, document.getTextLength())));
                // 只有行注释/块注释才走注释补全
                boolean lineComment = before.endsWith("//") || (before.contains("//")
                        && before.substring(before.lastIndexOf('\n') + 1).contains("//"));
                boolean blockComment = before.contains("/*") && !before.substring(before.lastIndexOf("/*")).contains("*/");
                if (!lineComment && !blockComment) {
                    REQUEST_IN_FLIGHT.set(false);
                    return;
                }
                LOG.info("enter-listener: direct comment completion request, lineComment=" + lineComment);
                String prompt = com.deepseek.plugin.prompt.JavaExpertPrompt.commentCompletionPrompt(before, after);
                com.deepseek.plugin.settings.DeepSeekState state =
                        com.deepseek.plugin.settings.DeepSeekState.getInstance();
                String key = state.apiKey.trim();
                if (key.isEmpty()) {
                    REQUEST_IN_FLIGHT.set(false);
                    return;
                }
                com.deepseek.plugin.client.DeepSeekClient.StreamRequest req =
                        new com.deepseek.plugin.client.DeepSeekClient.StreamRequest();
                req.apiKey = key;
                req.baseUrl = state.baseUrl;
                req.model = state.model;
                req.temperature = 0.7;
                req.maxTokens = 128;
                req.messages = java.util.List.of(
                        com.deepseek.plugin.client.ChatMessage.system(
                                "你是「DeepSeek Java Expert」，在 IDE 中提供注释补全，只输出纯文本注释内容，不要代码块标记。"),
                        com.deepseek.plugin.client.ChatMessage.user(prompt));
                com.deepseek.plugin.status.PluginStatus.getInstance()
                        .setReqState(com.deepseek.plugin.status.PluginStatus.ReqState.REQUESTING);
                com.deepseek.plugin.client.DeepSeekClient.getInstance().chatStream(req,
                        new com.deepseek.plugin.client.ChatStreamListener() {
                            @Override
                            public void onDelta(String delta) {
                            }

                            @Override
                            public void onFinish(String fullText) {
                                try {
                                    com.deepseek.plugin.status.PluginStatus.getInstance()
                                            .setReqState(com.deepseek.plugin.status.PluginStatus.ReqState.DONE);
                                    if (fullText == null || fullText.isBlank()) {
                                        return;
                                    }
                                    ApplicationManager.getApplication().invokeLater(() -> {
                                        try {
                                            if (project.isDisposed() || editor.isDisposed()) {
                                                return;
                                            }
                                            String suggestion = cleanSuggestion(fullText);
                                            if (suggestion.isEmpty()) {
                                                return;
                                            }
                                            // 插入前再查一次光标：用户已移走则放弃
                                            if (!isCommentContextStillValid()) {
                                                LOG.info("enter-listener: caret moved, skip insert");
                                                return;
                                            }
                                            LOG.info("enter-listener: auto-inserting comment: " + suggestion.replace("\n", "\\n").substring(0, Math.min(60, suggestion.length())));
                                            // 标记"本次由插件插入"（静态共享，防止其他实例循环触发）
                                            AtomicBoolean inserting = INSERTING_MAP.computeIfAbsent(document, d -> new AtomicBoolean(false));
                                            inserting.set(true);
                                            AtomicLong last = LAST_INSERT_MS.computeIfAbsent(document, d -> new AtomicLong(0));
                                            last.set(System.currentTimeMillis());
                                            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, () -> {
                                                if (editor.isDisposed()) {
                                                    return;
                                                }
                                                int off = editor.getCaretModel().getOffset();
                                                document.insertString(off, suggestion);
                                                editor.getCaretModel().moveToOffset(off + suggestion.length());
                                            });
                                            // 短暂延迟后清除插入标记
                                            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                                                try {
                                                    Thread.sleep(600);
                                                } catch (InterruptedException e) {
                                                    return;
                                                }
                                                inserting.set(false);
                                            });
                                        } finally {
                                            REQUEST_IN_FLIGHT.set(false);
                                        }
                                    });
                                } catch (Throwable t) {
                                    LOG.warn("enter-listener: comment insert failed", t);
                                    REQUEST_IN_FLIGHT.set(false);
                                }
                            }

                            @Override
                            public void onError(String message, Throwable cause) {
                                LOG.warn("enter-listener: comment completion error: " + message);
                                REQUEST_IN_FLIGHT.set(false);
                            }
                        });
            } catch (Throwable t) {
                LOG.warn("enter-listener: direct comment completion failed", t);
                REQUEST_IN_FLIGHT.set(false);
            }
        }

        /** 插入前校验：光标前仍是注释上下文（// 行注释或未闭合 /* 块注释）。 */
        private boolean isCommentContextStillValid() {
            try {
                int offset = editor.getCaretModel().getOffset();
                String before = com.intellij.openapi.application.ReadAction.compute(() ->
                        document.getText(new com.intellij.openapi.util.TextRange(0, offset)));
                boolean lineComment = before.endsWith("//") || (before.contains("//")
                        && before.substring(before.lastIndexOf('\n') + 1).contains("//"));
                boolean blockComment = before.contains("/*") && !before.substring(before.lastIndexOf("/*")).contains("*/");
                return lineComment || blockComment;
            } catch (Throwable t) {
                return false;
            }
        }

        private static int indexOfNewline(CharSequence s) {
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) == '\n') {
                    return i;
                }
            }
            return -1;
        }
    }
}
