package com.deepseek.plugin.actions;

import com.deepseek.plugin.prompt.JavaExpertPrompt;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;

/**
 * 为选中的代码生成 JUnit 单元测试。
 * 「应用」将测试代码追加到当前文件末尾。
 */
public class GenerateTestAction extends DeepSeekActionBase {

    @Override
    protected void perform(Project project, Editor editor, PsiFile file) {
        String code = selectedOrEnclosing(editor, file);
        runChat(project, editor, file, "DeepSeek: 生成单元测试",
                JavaExpertPrompt.generateTestPrompt(code), true,
                reply -> appendTestToFile(project, editor, reply));
    }

    private void appendTestToFile(Project project, Editor editor, String reply) {
        String clean = JavaExpertPrompt.extractCodeBlock(reply);
        if (clean.isEmpty()) return;
        Document doc = editor.getDocument();
        WriteCommandAction.runWriteCommandAction(project, () -> {
            String text = doc.getText();
            String sep = text.isEmpty() || text.endsWith("\n") ? "" : "\n";
            doc.insertString(doc.getTextLength(), sep + "\n// ===== 由 DeepSeek 生成的测试 =====\n" + clean + "\n");
        });
    }
}
