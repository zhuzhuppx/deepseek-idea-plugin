package com.deepseek.plugin.actions;

import com.deepseek.plugin.client.ChatMessage;
import com.deepseek.plugin.client.ChatStreamListener;
import com.deepseek.plugin.client.DeepSeekClient;
import com.deepseek.plugin.memory.MemoryStore;
import com.deepseek.plugin.prompt.JavaExpertPrompt;
import com.deepseek.plugin.settings.DeepSeekState;
import com.deepseek.plugin.ui.ResultDialog;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * DeepSeek 动作基类：统一校验配置、收集上下文、发起流式请求并展示结果。
 */
public abstract class DeepSeekActionBase extends AnAction {

    protected abstract void perform(Project project, Editor editor, PsiFile file);

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile file = e.getData(CommonDataKeys.PSI_FILE);
        if (project == null || editor == null || file == null) {
            Messages.showErrorDialog("请在编辑器中使用此功能。", "DeepSeek");
            return;
        }
        if (!DeepSeekState.getInstance().apiKey.trim().isEmpty()) {
            perform(project, editor, file);
        } else {
            Messages.showErrorDialog(project,
                    "尚未配置 DeepSeek API Key。\n请前往 Settings → Tools → DeepSeek Assistant 填写。",
                    "DeepSeek 未配置");
        }
    }

    /** 选中文本；无选中时取所在方法；再不行取当前行。 */
    protected String selectedOrEnclosing(Editor editor, PsiFile file) {
        String sel = editor.getSelectionModel().getSelectedText();
        if (sel != null && !sel.isBlank()) return sel;
        PsiElement el = file.findElementAt(editor.getCaretModel().getOffset());
        if (el != null) {
            PsiMethod method = PsiTreeUtil.getParentOfType(el, PsiMethod.class);
            if (method != null && !method.isConstructor()) return method.getText();
        }
        int offset = editor.getCaretModel().getOffset();
        Document doc = editor.getDocument();
        int line = doc.getLineNumber(offset);
        return doc.getText(new TextRange(doc.getLineStartOffset(line), doc.getLineEndOffset(line)));
    }

    /** 发起流式请求并展示到结果对话框。 */
    protected void runChat(Project project, Editor editor, PsiFile file,
                           String title, String userPrompt, boolean hasApply,
                           Consumer<String> applyHandler) {
        runChat(project, editor, file, title, userPrompt, hasApply, applyHandler, null);
    }

    /** 发起流式请求并展示到结果对话框；onResult 在流结束后收到完整回复。 */
    protected void runChat(Project project, Editor editor, PsiFile file,
                           String title, String userPrompt, boolean hasApply,
                           Consumer<String> applyHandler, Consumer<String> onResult) {
        String system = JavaExpertPrompt.buildSystemPrompt(project);
        String projectContext = JavaExpertPrompt.buildProjectContext(project, file);
        String fullUserPrompt = projectContext.isEmpty() ? userPrompt : projectContext + "\n\n" + userPrompt;

        List<ChatMessage> messages = List.of(
                ChatMessage.system(system),
                ChatMessage.user(fullUserPrompt));

        ResultDialog dialog = new ResultDialog(project, title, hasApply);
        if (applyHandler != null) {
            dialog.setApplyHandler(text -> applyHandler.accept(text));
        }
        dialog.show();

        DeepSeekState state = DeepSeekState.getInstance();
        DeepSeekClient.StreamRequest req = new DeepSeekClient.StreamRequest();
        req.apiKey = state.apiKey;
        req.baseUrl = state.baseUrl;
        req.model = state.model;
        req.temperature = state.temperature;
        req.maxTokens = state.maxTokens;
        req.messages = messages;

        CompletableFuture<String> future = DeepSeekClient.getInstance().chatStream(req, new ChatStreamListener() {
            @Override
            public void onDelta(String delta) {
                dialog.append(delta);
            }

            @Override
            public void onFinish(String fullText) {
                dialog.finish();
                remember(project, userPrompt, fullText);
                if (onResult != null) {
                    onResult.accept(fullText);
                }
            }

            @Override
            public void onError(String message, Throwable cause) {
                dialog.fail(message);
            }
        });
        dialog.setCancelHandler(() -> future.cancel(true));
    }

    /** 将本轮对话沉淀到记忆（最近对话记录）。 */
    private void remember(Project project, String userPrompt, String fullText) {
        if (!DeepSeekState.getInstance().memoryEnabled) return;
        MemoryStore store = MemoryStore.getInstance();
        String projectId = project.getBasePath();
        String brief = userPrompt.length() > 600 ? userPrompt.substring(0, 600) : userPrompt;
        store.appendConversation(projectId, "user", brief);
        store.appendConversation(projectId, "assistant",
                fullText.length() > 600 ? fullText.substring(0, 600) : fullText);
    }

    /** 应用代码：有选区则替换选区，否则插入到光标处。 */
    protected void applyCode(Project project, Editor editor, String reply) {
        String clean = JavaExpertPrompt.extractCodeBlock(reply);
        if (clean.isEmpty()) return;
        Document doc = editor.getDocument();
        WriteCommandAction.runWriteCommandAction(project, () -> {
            SelectionModel sel = editor.getSelectionModel();
            if (sel.hasSelection()) {
                doc.replaceString(sel.getSelectionStart(), sel.getSelectionEnd(), clean);
            } else {
                doc.insertString(editor.getCaretModel().getOffset(), clean);
            }
        });
    }
}
