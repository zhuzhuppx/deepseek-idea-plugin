package com.deepseek.plugin.actions;

import com.deepseek.plugin.context.CodeContextCollector;
import com.deepseek.plugin.prompt.JavaExpertPrompt;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;

/**
 * 根据光标所在注释生成实现代码，「应用」插入到注释之后。
 */
public class CommentToCodeAction extends DeepSeekActionBase {

    @Override
    protected void perform(Project project, Editor editor, PsiFile file) {
        CodeContextCollector.EditorContext ctx = CodeContextCollector.collect(editor, file);
        String comment = ctx.commentText;
        if (comment == null || comment.isBlank()) {
            com.intellij.openapi.ui.Messages.showInfoMessage(project,
                    "请把光标放到注释上再使用此功能。", "DeepSeek: 根据注释生成代码");
            return;
        }
        runChat(project, editor, file, "DeepSeek: 根据注释生成代码",
                JavaExpertPrompt.commentToCodePrompt(comment, ctx), true,
                reply -> applyCode(project, editor, reply));
    }
}
