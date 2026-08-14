package com.deepseek.plugin.inspection;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollingModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;

/**
 * Bug 问题列表对话框：点击「定位」跳转到对应行，支持查看修复建议与清除标注。
 */
public class BugIssuesDialog extends DialogWrapper {

    private final Editor editor;
    private final List<BugScanner.BugIssue> issues;
    private final List<RangeHighlighter> highlighters;
    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private final JBList<String> issueList = new JBList<>(listModel);
    private final JTextArea detail = new JTextArea(6, 60);

    public BugIssuesDialog(@Nullable Project project, Editor editor,
                           List<BugScanner.BugIssue> issues, List<RangeHighlighter> highlighters) {
        super(project, false);
        this.editor = editor;
        this.issues = issues;
        this.highlighters = highlighters;
        setTitle("DeepSeek: Bug 扫描结果 (" + issues.size() + " 个问题)");
        detail.setEditable(false);
        detail.setLineWrap(true);
        for (BugScanner.BugIssue issue : issues) {
            listModel.addElement(issue.toString());
        }
        issueList.setSelectedIndex(0);
        issueList.addListSelectionListener(e -> showDetail(issueList.getSelectedIndex()));
        showDetail(0);
        init();
    }

    private void showDetail(int index) {
        if (index < 0 || index >= issues.size()) {
            detail.setText("");
            return;
        }
        BugScanner.BugIssue issue = issues.get(index);
        StringBuilder sb = new StringBuilder();
        sb.append("行 ").append(issue.line).append("  严重程度: ")
                .append("error".equalsIgnoreCase(issue.severity) ? "错误" : "警告").append('\n');
        sb.append("描述: ").append(issue.description).append('\n');
        if (issue.suggestion != null && !issue.suggestion.isBlank()) {
            sb.append("建议: \n").append(issue.suggestion);
        }
        detail.setText(sb.toString());
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JLabel hint = new JLabel("问题已标注到编辑器（红色=错误，黄色=警告）");
        panel.add(hint, BorderLayout.NORTH);
        panel.add(new JBScrollPane(issueList), BorderLayout.CENTER);
        panel.add(new JBScrollPane(detail), BorderLayout.SOUTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton jump = new JButton("定位到行");
        JButton clear = new JButton("清除标注");
        JButton copy = new JButton("复制描述");
        jump.addActionListener(e -> jumpToSelected());
        clear.addActionListener(e -> {
            BugScanner.clearHighlights(editor, highlighters);
            clear.setEnabled(false);
        });
        copy.addActionListener(e -> {
            int idx = issueList.getSelectedIndex();
            if (idx >= 0 && idx < issues.size()) {
                BugScanner.BugIssue issue = issues.get(idx);
                java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new java.awt.datatransfer.StringSelection(
                                issue.toString() + "\n建议: " + issue.suggestion), null);
            }
        });
        buttons.add(copy);
        buttons.add(jump);
        buttons.add(clear);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private void jumpToSelected() {
        int idx = issueList.getSelectedIndex();
        if (idx < 0 || idx >= issues.size() || editor == null || editor.isDisposed()) return;
        int line = Math.min(issues.get(idx).line, editor.getDocument().getLineCount()) - 1;
        if (line < 0) return;
        int offset = editor.getDocument().getLineStartOffset(line);
        editor.getCaretModel().moveToOffset(offset);
        ScrollingModel scrolling = editor.getScrollingModel();
        scrolling.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER);
        editor.getContentComponent().requestFocus();
    }

    @Override
    protected void dispose() {
        BugScanner.clearHighlights(editor, highlighters);
        super.dispose();
    }
}
