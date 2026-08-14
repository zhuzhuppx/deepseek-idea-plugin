package com.deepseek.plugin.actions;

import com.deepseek.plugin.prompt.JavaExpertPrompt;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;

/**
 * 优化重构建议：输出优化后的代码，可一键替换选区。
 */
public class OptimizeCodeAction extends DeepSeekActionBase {

    @Override
    protected void perform(Project project, Editor editor, PsiFile file) {
        String code = selectedOrEnclosing(editor, file);
        runChat(project, editor, file, "DeepSeek: 优化重构建议",
                JavaExpertPrompt.optimizePrompt(code), true,
                reply -> applyCode(project, editor, reply));
    }
}
