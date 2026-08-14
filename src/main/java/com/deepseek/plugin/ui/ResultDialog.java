package com.deepseek.plugin.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.function.Consumer;

/**
 * AI 回复结果对话框：流式展示，支持停止 / 复制 / 应用（将代码插入编辑器）。
 */
public class ResultDialog extends DialogWrapper {

    private final JEditorPane editorPane = new JEditorPane();
    private final StringBuilder buffer = new StringBuilder();
    private final JButton copyButton = new JButton("复制");
    private final JButton applyButton = new JButton("应用");
    private final JButton stopButton = new JButton("停止");
    private final boolean hasApply;

    private volatile String finalText = "";
    private volatile boolean finished;
    private Runnable cancelHandler;
    private Consumer<String> applyHandler;

    public ResultDialog(@Nullable Project project, String title, boolean hasApply) {
        super(project, false);
        this.hasApply = hasApply;
        setTitle(title);
        editorPane.setContentType("text/html");
        editorPane.setEditable(false);
        editorPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        copyButton.setEnabled(false);
        applyButton.setEnabled(false);
        applyButton.setVisible(hasApply);
        stopButton.setEnabled(false);

        copyButton.addActionListener(e -> {
            StringSelection sel = new StringSelection(finalText);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
        });
        applyButton.addActionListener(e -> {
            if (applyHandler != null) applyHandler.accept(finalText);
            close(OK_EXIT_CODE);
        });
        stopButton.addActionListener(e -> {
            if (cancelHandler != null) cancelHandler.run();
            if (!finished) {
                finished = true;
                finalText = buffer.toString();
                updateButtons();
            }
        });
        init();
    }

    public void setCancelHandler(Runnable cancelHandler) {
        this.cancelHandler = cancelHandler;
        SwingUtilities.invokeLater(() -> stopButton.setEnabled(true));
    }

    public void setApplyHandler(Consumer<String> applyHandler) {
        this.applyHandler = applyHandler;
    }

    /** 线程安全：可在任意线程调用。 */
    public void append(String delta) {
        SwingUtilities.invokeLater(() -> {
            if (finished) return;
            buffer.append(delta);
            editorPane.setText("<html><body style='font-family:Monospaced;font-size:12px'>"
                    + escapeHtml(buffer.toString()).replace("\n", "<br>") + "</body></html>");
        });
    }

    /** 线程安全：可在任意线程调用。 */
    public void finish() {
        SwingUtilities.invokeLater(() -> {
            finished = true;
            finalText = buffer.toString();
            updateButtons();
        });
    }

    /** 线程安全：可在任意线程调用。 */
    public void fail(String message) {
        SwingUtilities.invokeLater(() -> {
            finished = true;
            buffer.append("\n\n[错误] ").append(message);
            finalText = buffer.toString();
            editorPane.setText("<html><body style='font-family:Monospaced;font-size:12px'>"
                    + escapeHtml(buffer.toString()).replace("\n", "<br>") + "</body></html>");
            updateButtons();
        });
    }

    private void updateButtons() {
        copyButton.setEnabled(!finalText.isEmpty());
        applyButton.setEnabled(!finalText.isEmpty());
        stopButton.setEnabled(false);
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new java.awt.Dimension(760, 480));
        panel.add(new JBScrollPane(editorPane), BorderLayout.CENTER);

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(copyButton);
        south.add(applyButton);
        south.add(stopButton);
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
