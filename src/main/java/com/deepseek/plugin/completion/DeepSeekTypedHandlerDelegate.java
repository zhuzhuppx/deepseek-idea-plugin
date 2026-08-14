package com.deepseek.plugin.completion;

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.TypedActionHandler;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

/**
 * 显式/字符触发：拦截关键字符（. // try）后，调度场景化补全。
 * 相比轮询，TypedHandlerDelegate 更高效精准。
 */
public class DeepSeekTypedHandlerDelegate extends TypedHandlerDelegate {

    private static final com.intellij.openapi.diagnostic.Logger LOG =
            com.intellij.openapi.diagnostic.Logger.getInstance(DeepSeekTypedHandlerDelegate.class);

    @Override
    public @NotNull Result charTyped(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        // 触发的字符：. （链式调用）、/（注释）、try 由输入序列处理
        if (c == '.') {
            // 链式调用：输入 . 后停顿触发（防抖由 provider 内部处理）
            scheduleTrigger(project, editor, 250);
        }
        return Result.CONTINUE;
    }

    /** 延迟触发一次手动补全（等价于用户手动唤起）。 */
    private static void scheduleTrigger(Project project, Editor editor, long delayMs) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                return;
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed() || editor.isDisposed()) {
                    return;
                }
                try {
                    com.intellij.codeInsight.inline.completion.action.CallInlineCompletionAction action =
                            new com.intellij.codeInsight.inline.completion.action.CallInlineCompletionAction();
                    java.util.Map<String, Object> dataMap = new java.util.HashMap<>();
                    dataMap.put(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR.getName(), editor);
                    dataMap.put(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.getName(), project);
                    com.intellij.openapi.actionSystem.DataContext dataContext =
                            com.intellij.openapi.actionSystem.impl.SimpleDataContext.getSimpleContext(dataMap, null);
                    com.intellij.openapi.actionSystem.AnActionEvent event =
                            com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
                                    "deepseek.typed.trigger", null, dataContext);
                    action.actionPerformed(event);
                } catch (Throwable t) {
                    LOG.warn("typed trigger failed", t);
                }
            });
        });
    }
}
