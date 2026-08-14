package com.deepseek.plugin.actions;

import com.deepseek.plugin.prompt.JavaExpertPrompt;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;

/**
 * 解释选中的代码。
 */
public class ExplainCodeAction extends DeepSeekActionBase {

    @Override
    protected void perform(Project project, Editor editor, PsiFile file) {
        String code = selectedOrEnclosing(editor, file);
        runChat(project, editor, file, "DeepSeek: 解释代码",
                JavaExpertPrompt.explainPrompt(code), false, null);
    }
}
