package com.deepseek.plugin.status;

import com.intellij.openapi.application.ApplicationManager;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 插件全局状态：连接状态 + 请求状态。
 * 供状态栏组件显示，供各功能模块更新。
 */
public final class PluginStatus {

    public enum ConnState {
        NO_KEY("未配置Key", "#ED6A5A"),
        CONNECTING("连接中…", "#E8A33D"),
        CONNECTED("已连接", "#4CAF50"),
        FAILED("连接失败", "#ED6A5A");

        final String label;
        final String color;

        ConnState(String label, String color) {
            this.label = label;
            this.color = color;
        }
    }

    public enum ReqState {
        IDLE(""),
        REQUESTING("请求中…"),
        DONE("完成"),
        ERROR("请求失败");

        final String label;

        ReqState(String label) {
            this.label = label;
        }
    }

    private volatile ConnState connState = ConnState.NO_KEY;
    private volatile ReqState reqState = ReqState.IDLE;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public interface Listener {
        void onStatusChanged();
    }

    public static PluginStatus getInstance() {
        return ApplicationManager.getApplication().getService(PluginStatus.class);
    }

    public ConnState getConnState() {
        return connState;
    }

    public ReqState getReqState() {
        return reqState;
    }

    public void setConnState(ConnState state) {
        this.connState = state;
        notifyListeners();
    }

    public void setReqState(ReqState state) {
        this.reqState = state;
        notifyListeners();
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyListeners() {
        for (Listener l : listeners) {
            try {
                l.onStatusChanged();
            } catch (Throwable ignore) {
            }
        }
    }
}
