package com.deepseek.plugin.completion;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 补全结果缓存：相同 PSI 节点标识 + 前缀 在 5 秒内复用，避免重复调用模型。
 */
public final class CompletionCache {

    private static final int MAX_SIZE = 100;
    private static final long TTL_MS = 5000;

    private static final Map<String, CacheEntry> CACHE = new LinkedHashMap<>(MAX_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > MAX_SIZE;
        }
    };

    private CompletionCache() {
    }

    /** 缓存键：PSI 标识 + 光标前文本尾部（前 60 字符）作为前缀指纹。 */
    public static String keyOf(String psiFingerprint, String beforeCaret) {
        String tail = beforeCaret == null ? "" : beforeCaret;
        if (tail.length() > 60) {
            tail = tail.substring(tail.length() - 60);
        }
        return psiFingerprint + "|" + tail;
    }

    public static String get(String key) {
        synchronized (CACHE) {
            CacheEntry e = CACHE.get(key);
            if (e == null) {
                return null;
            }
            if (System.currentTimeMillis() - e.timestamp > TTL_MS) {
                CACHE.remove(key);
                return null;
            }
            return e.result;
        }
    }

    public static void put(String key, String result) {
        synchronized (CACHE) {
            CACHE.put(key, new CacheEntry(result, System.currentTimeMillis()));
        }
    }

    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    private static final class CacheEntry {
        final String result;
        final long timestamp;

        CacheEntry(String result, long timestamp) {
            this.result = result;
            this.timestamp = timestamp;
        }
    }
}
