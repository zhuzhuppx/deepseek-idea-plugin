package com.deepseek.plugin.actions;

import com.deepseek.plugin.inspection.BugIssuesDialog;
import com.deepseek.plugin.inspection.BugScanner;
import com.deepseek.plugin.prompt.JavaExpertPrompt;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;

import java.util.ArrayList;
import java.util.List;

/**
 * 扫描当前文件 Bug：AI 返回问题列表后标注到编辑器并弹出问题对话框。
 */
public class ScanBugsAction extends DeepSeekActionBase {

    @Override
    protected void perform(Project project, Editor editor, PsiFile file) {
        String code = selectedOrEnclosing(editor, file);
        List<RangeHighlighter> highlighters = new ArrayList<>();
        runChat(project, editor, file, "DeepSeek: 扫描当前文件 Bug",
                JavaExpertPrompt.scanBugsPrompt(code), false, null,
                reply -> showResults(project, editor, reply, highlighters));
    }

    private void showResults(Project project, Editor editor, String reply,
                             List<RangeHighlighter> highlighters) {
        List<BugScanner.BugIssue> issues = BugScanner.parse(reply);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (issues.isEmpty()) {
                com.intellij.openapi.ui.Messages.showInfoMessage(project,
                        "未发现明确的 Bug，或 AI 未按格式返回。\n\n原始回复：\n" + reply, "DeepSeek: Bug 扫描");
                return;
            }
            List<RangeHighlighter> applied = BugScanner.applyHighlights(editor, issues);
            highlighters.addAll(applied);
            new BugIssuesDialog(project, editor, issues, highlighters).show();
        });
    }
}
