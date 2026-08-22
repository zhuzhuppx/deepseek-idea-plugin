package com.deepseek.plugin.status;

import com.deepseek.plugin.settings.DeepSeekState;
import com.intellij.ide.ApplicationInitializedListener;
import com.intellij.openapi.diagnostic.Logger;

/**
 * 插件启动后自动恢复连接状态：
 * 已保存 API Key 时直接标记"已连接"。
 * 之前 ConnState 默认一直是 NO_KEY，导致状态栏显示"未配置Key"误导用户，
 * 用户以为要先去设置里点"测试连接"才能用补全（补全实际只依赖 state.apiKey）。
 */
public final class ConnectionAutoInitListener implements ApplicationInitializedListener {

    private static final Logger LOG = Logger.getInstance(ConnectionAutoInitListener.class);

    @Override
    public void componentsInitialized() {
        DeepSeekState state = DeepSeekState.getInstance();
        if (state.apiKey != null && !state.apiKey.trim().isEmpty()) {
            PluginStatus.getInstance().setConnState(PluginStatus.ConnState.CONNECTED);
            LOG.info("connection auto-init: api key present, state=CONNECTED");
        } else {
            LOG.info("connection auto-init: no api key, keep NO_KEY");
        }
    }
}
