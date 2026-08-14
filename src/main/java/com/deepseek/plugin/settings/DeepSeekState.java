package com.deepseek.plugin.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 插件全局配置，持久化到 IDE 配置目录。
 */
@State(name = "DeepSeekJavaExpertSettings", storages = @Storage("deepseek-java-expert.xml"))
@Service
public final class DeepSeekState implements PersistentStateComponent<DeepSeekState> {

    public String apiKey = "";
    public String baseUrl = "https://api.deepseek.com";
    public String model = "deepseek-v4-flash";
    public double temperature = 0.2;
    public int maxTokens = 2048;
    public int connectTimeoutMs = 15000;

    // 行内补全
    public boolean completionEnabled = true;
    public int completionDelayMs = 800;
    public int completionMaxLines = 30;

    // 项目上下文
    public boolean projectContextEnabled = true;
    public int contextMaxFileChars = 20000;
    public int contextMaxRelatedFiles = 5;

    // 记忆
    public boolean memoryEnabled = true;
    public int memoryMaxFacts = 20;
    public int memoryRecentExchanges = 6;

    public static DeepSeekState getInstance() {
        return com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(DeepSeekState.class);
    }

    @Override
    public @Nullable DeepSeekState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull DeepSeekState state) {
        XmlSerializerUtil.copyBean(state, this);
    }
}
