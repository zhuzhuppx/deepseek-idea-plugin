package com.deepseek.plugin.settings;

import com.deepseek.plugin.client.ChatMessage;
import com.deepseek.plugin.client.DeepSeekClient;
import com.deepseek.plugin.context.ProjectScanner;
import com.deepseek.plugin.memory.MemoryStore;
import com.deepseek.plugin.memory.MemoryStore.Fact;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 设置面板 UI。纯 Swing 代码构建，不依赖 .form 文件。
 */
public class DeepSeekSettingsForm {

    private static final Logger LOG = Logger.getInstance(DeepSeekSettingsForm.class);

    private final JPanel root;
    private final JPasswordField apiKeyField = new JPasswordField();
    private final JBTextField baseUrlField = new JBTextField();
    private final JComboBox<String> modelCombo = new JComboBox<>(
            new String[]{"deepseek-v4-flash", "deepseek-v4-pro"});
    private final JSpinner temperatureSpinner = new JSpinner(new SpinnerNumberModel(0.2, 0.0, 2.0, 0.1));
    private final JSpinner maxTokensSpinner = new JSpinner(new SpinnerNumberModel(2048, 128, 8192, 128));
    private final JCheckBox completionEnabledBox = new JCheckBox("启用行内补全");
    private final JSpinner completionDelaySpinner = new JSpinner(new SpinnerNumberModel(800, 0, 5000, 100));
    private final JCheckBox projectContextBox = new JCheckBox("注入全项目上下文（结构/依赖/相关文件）");
    private final JCheckBox memoryEnabledBox = new JCheckBox("启用记忆系统");
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton testButton = new JButton("测试连接");
    private final AtomicBoolean testing = new AtomicBoolean(false);

    // 记忆管理
    private final DefaultListModel<String> factsModel = new DefaultListModel<>();
    private final JList<String> factsList = new JBList<>(factsModel);
    private final JTextArea factInput = new JTextArea(3, 40);

    public DeepSeekSettingsForm() {
        modelCombo.setEditable(true);
        JPanel basic = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("API Key（platform.deepseek.com 获取）:"), apiKeyField)
                .addLabeledComponent(new JBLabel("Base URL:"), baseUrlField)
                .addLabeledComponent(new JBLabel("模型:"), modelCombo)
                .addLabeledComponent(new JBLabel("温度:"), temperatureSpinner)
                .addLabeledComponent(new JBLabel("最大输出 tokens:"), maxTokensSpinner)
                .addLabeledComponent(new JBLabel("补全防抖延迟(ms):"), completionDelaySpinner)
                .addComponent(completionEnabledBox)
                .addComponent(projectContextBox)
                .addComponent(memoryEnabledBox)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();

        JPanel testPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        testPanel.add(testButton);
        testPanel.add(statusLabel);
        testButton.addActionListener(e -> testConnection());

        // 记忆管理面板
        JPanel memoryPanel = new JPanel(new BorderLayout(JBUI.scale(8), JBUI.scale(8)));
        memoryPanel.add(new JBLabel("记忆条目（全局 + 各项目，注入 AI 提示词）:"), BorderLayout.NORTH);
        memoryPanel.add(new JBScrollPane(factsList), BorderLayout.CENTER);
        JPanel memoryEdit = new JPanel(new BorderLayout(JBUI.scale(4), JBUI.scale(4)));
        factInput.setLineWrap(true);
        memoryEdit.add(new JBScrollPane(factInput), BorderLayout.CENTER);
        JPanel memoryButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addFact = new JButton("添加记忆");
        JButton removeFact = new JButton("删除选中");
        JButton clearConv = new JButton("清空对话历史");
        memoryButtons.add(addFact);
        memoryButtons.add(removeFact);
        memoryButtons.add(clearConv);
        memoryEdit.add(memoryButtons, BorderLayout.SOUTH);
        memoryPanel.add(memoryEdit, BorderLayout.SOUTH);
        addFact.addActionListener(e -> addFact());
        removeFact.addActionListener(e -> removeSelectedFact());
        clearConv.addActionListener(e -> clearConversations());

        // 项目信息面板
        JPanel projectPanel = new JPanel(new BorderLayout(JBUI.scale(8), JBUI.scale(8)));
        JTextArea projectInfo = new JTextArea(12, 60);
        projectInfo.setEditable(false);
        projectInfo.setLineWrap(true);
        JButton refreshProject = new JButton("扫描当前打开的项目");
        projectPanel.add(new JBScrollPane(projectInfo), BorderLayout.CENTER);
        JPanel projBottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
        projBottom.add(refreshProject);
        JLabel projStatus = new JLabel(" ");
        projBottom.add(projStatus);
        projectPanel.add(projBottom, BorderLayout.SOUTH);
        refreshProject.addActionListener(e -> {
            Project p = firstOpenProject();
            if (p == null) {
                projStatus.setText("没有打开的项目");
                return;
            }
            projStatus.setText("扫描中…");
            com.intellij.openapi.application.ApplicationManager.getApplication()
                    .executeOnPooledThread(() -> {
                        String info = ProjectScanner.buildProjectContext(p, null,
                                DeepSeekState.getInstance().contextMaxRelatedFiles,
                                DeepSeekState.getInstance().contextMaxFileChars);
                        com.intellij.openapi.application.ApplicationManager.getApplication()
                                .invokeLater(() -> {
                                    projectInfo.setText(info);
                                    projStatus.setText("完成");
                                }, com.intellij.openapi.application.ModalityState.any());
                    });
        });

        JTabbedPane tabs = new JTabbedPane();
        JPanel basicWithTest = new JPanel(new BorderLayout());
        basicWithTest.add(basic, BorderLayout.CENTER);
        basicWithTest.add(testPanel, BorderLayout.SOUTH);
        tabs.addTab("通用", basicWithTest);
        tabs.addTab("记忆管理", memoryPanel);
        tabs.addTab("项目信息", projectPanel);

        root = new JPanel(new BorderLayout());
        root.add(tabs, BorderLayout.CENTER);
    }

    public JComponent getRoot() {
        return root;
    }

    private static Project firstOpenProject() {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        return projects.length > 0 ? projects[0] : null;
    }

    private void testConnection() {
        LOG.info("testConnection clicked, key len=" + String.valueOf(apiKeyField.getPassword()).length());
        String key = String.valueOf(apiKeyField.getPassword()).trim();
        if (key.isEmpty()) {
            statusLabel.setText("请先填写 API Key");
            LOG.info("testConnection: empty key, skip");
            return;
        }
        // API Key 只能包含合法 HTTP header 字符（token 字符），否则 HttpRequest 构建会抛异常
        if (!key.matches("[A-Za-z0-9._~+/-]+")) {
            statusLabel.setText("API Key 格式不正确（包含非法字符）");
            LOG.warn("testConnection: invalid key format, len=" + key.length());
            return;
        }
        if (!testing.compareAndSet(false, true)) return;
        testButton.setEnabled(false);
        statusLabel.setText("连接中…");
        DeepSeekState state = DeepSeekState.getInstance();
        DeepSeekClient.StreamRequest req = new DeepSeekClient.StreamRequest();
        req.apiKey = key;
        req.baseUrl = baseUrlField.getText();
        req.model = String.valueOf(modelCombo.getSelectedItem());
        req.temperature = ((Number) temperatureSpinner.getValue()).doubleValue();
        req.maxTokens = (Integer) maxTokensSpinner.getValue();
        req.messages = List.of(ChatMessage.user("请回复:连接成功"));
        LOG.info("testConnection: sending request, model=" + req.model + " baseUrl=" + req.baseUrl);
        com.deepseek.plugin.status.PluginStatus.getInstance()
                .setConnState(com.deepseek.plugin.status.PluginStatus.ConnState.CONNECTING);
        try {
            DeepSeekClient.getInstance().chatOnce(req).whenComplete((text, err) -> {
                LOG.info("testConnection: future completed, err=" + (err == null ? "null" : err.getClass().getName() + ": " + err.getMessage()));
                // 使用 ModalityState.any()：测试连接按钮位于 Settings 模态对话框内，
                // 默认的 non-modal invokeLater 在对话框关闭前不会执行回调，导致状态一直停在"连接中…"
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
                    LOG.info("testConnection: UI update executing");
                    testing.set(false);
                    testButton.setEnabled(true);
                    if (err != null) {
                        statusLabel.setText("失败: " + rootMsg(err));
                        com.deepseek.plugin.status.PluginStatus.getInstance()
                                .setConnState(com.deepseek.plugin.status.PluginStatus.ConnState.FAILED);
                    } else {
                        String reply = (text == null ? "" : text).trim();
                        statusLabel.setText(reply.isEmpty() ? "连接成功" : "连接成功: " + reply);
                        com.deepseek.plugin.status.PluginStatus.getInstance()
                                .setConnState(com.deepseek.plugin.status.PluginStatus.ConnState.CONNECTED);
                    }
                }, com.intellij.openapi.application.ModalityState.any());
            });
        } catch (Throwable t) {
            LOG.warn("testConnection: exception before sendAsync", t);
            testing.set(false);
            testButton.setEnabled(true);
            statusLabel.setText("失败: " + rootMsg(t));
            com.deepseek.plugin.status.PluginStatus.getInstance()
                    .setConnState(com.deepseek.plugin.status.PluginStatus.ConnState.FAILED);
        }
    }

    private static String rootMsg(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    // ---------- 记忆管理 ----------

    private void reloadFacts() {
        factsModel.clear();
        Project p = firstOpenProject();
        String projectId = p == null ? null : p.getBasePath();
        List<Fact> facts = MemoryStore.getInstance().getFacts(projectId);
        for (Fact f : facts) {
            String scope = "*".equals(f.projectId) ? "[全局] " : "[本项目] ";
            factsModel.addElement(scope + f.content);
        }
    }

    private void addFact() {
        String content = factInput.getText().trim();
        if (content.isEmpty()) return;
        Project p = firstOpenProject();
        MemoryStore.getInstance().addFact(p == null ? null : p.getBasePath(), content);
        factInput.setText("");
        reloadFacts();
    }

    private void removeSelectedFact() {
        int idx = factsList.getSelectedIndex();
        if (idx < 0) return;
        Project p = firstOpenProject();
        String projectId = p == null ? null : p.getBasePath();
        List<Fact> facts = MemoryStore.getInstance().getFacts(projectId);
        if (idx < facts.size()) {
            MemoryStore.getInstance().removeFact(facts.get(idx).id);
        }
        reloadFacts();
    }

    private void clearConversations() {
        Project p = firstOpenProject();
        MemoryStore.getInstance().clearConversation(p == null ? null : p.getBasePath());
    }

    // ---------- 加载/保存 ----------

    public void loadFrom(DeepSeekState state) {
        apiKeyField.setText(state.apiKey);
        baseUrlField.setText(state.baseUrl);
        modelCombo.setSelectedItem(state.model);
        temperatureSpinner.setValue(state.temperature);
        maxTokensSpinner.setValue(state.maxTokens);
        completionEnabledBox.setSelected(state.completionEnabled);
        completionDelaySpinner.setValue(state.completionDelayMs);
        projectContextBox.setSelected(state.projectContextEnabled);
        memoryEnabledBox.setSelected(state.memoryEnabled);
        reloadFacts();
    }

    public void applyTo(DeepSeekState state) {
        state.apiKey = String.valueOf(apiKeyField.getPassword()).trim();
        state.baseUrl = baseUrlField.getText().trim();
        state.model = String.valueOf(modelCombo.getSelectedItem()).trim();
        state.temperature = ((Number) temperatureSpinner.getValue()).doubleValue();
        state.maxTokens = (Integer) maxTokensSpinner.getValue();
        state.completionEnabled = completionEnabledBox.isSelected();
        state.completionDelayMs = (Integer) completionDelaySpinner.getValue();
        state.projectContextEnabled = projectContextBox.isSelected();
        state.memoryEnabled = memoryEnabledBox.isSelected();
    }

    public boolean isModified(DeepSeekState state) {
        if (!String.valueOf(apiKeyField.getPassword()).trim().equals(state.apiKey)) return true;
        if (!baseUrlField.getText().trim().equals(state.baseUrl)) return true;
        if (!String.valueOf(modelCombo.getSelectedItem()).trim().equals(state.model)) return true;
        if (((Number) temperatureSpinner.getValue()).doubleValue() != state.temperature) return true;
        if ((Integer) maxTokensSpinner.getValue() != state.maxTokens) return true;
        if (completionEnabledBox.isSelected() != state.completionEnabled) return true;
        if ((Integer) completionDelaySpinner.getValue() != state.completionDelayMs) return true;
        if (projectContextBox.isSelected() != state.projectContextEnabled) return true;
        if (memoryEnabledBox.isSelected() != state.memoryEnabled) return true;
        return false;
    }
}
