package com.deepseek.plugin.settings;

import com.intellij.openapi.options.Configurable;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;

/**
 * Settings → Tools → DeepSeek Java Expert 配置页。
 */
public class DeepSeekSettingsConfigurable implements Configurable {

    private DeepSeekSettingsForm form;

    @Override
    public String getDisplayName() {
        return "DeepSeek Java Expert";
    }

    @Override
    public @Nullable JComponent createComponent() {
        if (form == null) {
            form = new DeepSeekSettingsForm();
        }
        return form.getRoot();
    }

    @Override
    public boolean isModified() {
        return form != null && form.isModified(DeepSeekState.getInstance());
    }

    @Override
    public void apply() {
        if (form != null) {
            form.applyTo(DeepSeekState.getInstance());
        }
    }

    @Override
    public void reset() {
        if (form != null) {
            form.loadFrom(DeepSeekState.getInstance());
        }
    }

    @Override
    public void disposeUIResources() {
        form = null;
    }
}
