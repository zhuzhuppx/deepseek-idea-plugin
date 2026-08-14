package com.deepseek.plugin.status;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.event.MouseEvent;

/**
 * 状态栏组件：显示插件连接状态 + 请求状态。
 * 261 平台要求实现 getPresentation() 返回 WidgetPresentation。
 */
public class DeepSeekStatusBarWidget implements StatusBarWidget {

    private final PluginStatus status = PluginStatus.getInstance();
    private StatusBar statusBar;
    private String tooltipText = "";

    @Override
    public @NotNull String ID() {
        return "DeepSeek.JavaExpert.Status";
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        this.statusBar = statusBar;
        // 初始化连接状态：根据是否配置了 Key
        String key = com.deepseek.plugin.settings.DeepSeekState.getInstance().apiKey.trim();
        if (key.isEmpty()) {
            status.setConnState(PluginStatus.ConnState.NO_KEY);
        } else {
            if (status.getConnState() == PluginStatus.ConnState.NO_KEY) {
                status.setConnState(PluginStatus.ConnState.CONNECTED);
            }
        }
        status.addListener(this::updateFromStatus);
    }

    @Override
    public void dispose() {
        status.removeListener(this::updateFromStatus);
        statusBar = null;
    }

    private void updateFromStatus() {
        if (statusBar != null) {
            statusBar.updateWidget(ID());
        }
    }

    @Override
    public @Nullable WidgetPresentation getPresentation() {
        return new TextPresentation() {
            @Override
            public @NotNull String getText() {
                PluginStatus.ConnState conn = status.getConnState();
                PluginStatus.ReqState req = status.getReqState();
                StringBuilder sb = new StringBuilder("DeepSeek: ").append(conn.label);
                if (req != PluginStatus.ReqState.IDLE) {
                    sb.append(" · ").append(req.label);
                }
                tooltipText = sb.toString();
                return sb.toString();
            }

            @Override
            public @NotNull String getTooltipText() {
                return tooltipText;
            }

            @Override
            public float getAlignment() {
                return SwingConstants.CENTER;
            }

            @Override
            public @Nullable com.intellij.util.Consumer<MouseEvent> getClickConsumer() {
                return e -> {
                    if (statusBar != null) {
                        statusBar.updateWidget(ID());
                    }
                };
            }
        };
    }

    /**
     * Factory 注册（插件扩展点 statusBarWidgetFactory）。
     */
    public static class Factory implements StatusBarWidgetFactory {
        @Override
        public @NonNls @NotNull String getId() {
            return "DeepSeek.JavaExpert.Status";
        }

        @Override
        public @NotNull String getDisplayName() {
            return "老猿人 状态";
        }

        @Override
        public boolean isAvailable(@NotNull Project project) {
            return true;
        }

        @Override
        public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
            return new DeepSeekStatusBarWidget();
        }

        @Override
        public void disposeWidget(@NotNull StatusBarWidget widget) {
            widget.dispose();
        }

        @Override
        public boolean canBeEnabledOn(@NotNull StatusBar statusBar) {
            return true;
        }
    }
}
