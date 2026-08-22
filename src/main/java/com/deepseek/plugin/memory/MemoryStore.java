package com.deepseek.plugin.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 记忆系统：持久化到 IDE 配置目录 deepseek-ai/memory.json。
 * <ul>
 *   <li>facts：项目事实/偏好条目，projectId 为 null 表示全局记忆（注入所有项目）。可在设置面板增删。</li>
 *   <li>conversations：每个项目的最近对话记录（环形缓冲），自动沉淀。</li>
 * </ul>
 */
public class MemoryStore {

    private static final Logger LOG = Logger.getInstance(MemoryStore.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String GLOBAL = "*";

    private static volatile MemoryStore instance;

    private final Path file;
    private final Map<String, List<Fact>> facts = new LinkedHashMap<>();
    private final Map<String, List<Exchange>> conversations = new LinkedHashMap<>();

    public static MemoryStore getInstance() {
        MemoryStore local = instance;
        if (local == null) {
            synchronized (MemoryStore.class) {
                local = instance;
                if (local == null) {
                    local = new MemoryStore();
                    instance = local;
                }
            }
        }
        return local;
    }

    public MemoryStore() {
        Path dir = Path.of(PathManager.getConfigPath(), "deepseek-ai");
        this.file = dir.resolve("memory.json");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOG.warn("无法创建记忆目录", e);
        }
        load();
    }

    public static class Fact {
        public String id;
        public String projectId;   // null 或 "*" 表示全局
        public String content;
        public long createdAt;
    }

    public static class Exchange {
        public String role;
        public String content;
    }

    private synchronized void load() {
        if (!Files.exists(file)) return;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            JsonArray factArr = root.has("facts") ? root.getAsJsonArray("facts") : new JsonArray();
            for (JsonElement el : factArr) {
                JsonObject o = el.getAsJsonObject();
                Fact f = new Fact();
                f.id = o.has("id") ? o.get("id").getAsString() : UUID.randomUUID().toString();
                f.projectId = o.has("projectId") && !o.get("projectId").isJsonNull()
                        ? o.get("projectId").getAsString() : GLOBAL;
                f.content = o.has("content") ? o.get("content").getAsString() : "";
                f.createdAt = o.has("createdAt") ? o.get("createdAt").getAsLong() : System.currentTimeMillis();
                facts.computeIfAbsent(f.projectId, k -> new ArrayList<>()).add(f);
            }
            JsonObject convObj = root.has("conversations") ? root.getAsJsonObject("conversations") : new JsonObject();
            for (Map.Entry<String, JsonElement> entry : convObj.entrySet()) {
                List<Exchange> list = new ArrayList<>();
                for (JsonElement el : entry.getValue().getAsJsonArray()) {
                    JsonObject o = el.getAsJsonObject();
                    Exchange ex = new Exchange();
                    ex.role = o.has("role") ? o.get("role").getAsString() : "user";
                    ex.content = o.has("content") ? o.get("content").getAsString() : "";
                    list.add(ex);
                }
                conversations.put(entry.getKey(), list);
            }
        } catch (Exception e) {
            LOG.warn("加载记忆文件失败", e);
        }
    }

    private synchronized void save() {
        try {
            JsonObject root = new JsonObject();
            JsonArray factArr = new JsonArray();
            for (List<Fact> list : facts.values()) {
                for (Fact f : list) {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", f.id);
                    o.addProperty("projectId", f.projectId);
                    o.addProperty("content", f.content);
                    o.addProperty("createdAt", f.createdAt);
                    factArr.add(o);
                }
            }
            root.add("facts", factArr);
            JsonObject convObj = new JsonObject();
            for (Map.Entry<String, List<Exchange>> entry : conversations.entrySet()) {
                JsonArray arr = new JsonArray();
                for (Exchange ex : entry.getValue()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("role", ex.role);
                    o.addProperty("content", ex.content);
                    arr.add(o);
                }
                convObj.add(entry.getKey(), arr);
            }
            root.add("conversations", convObj);
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("保存记忆文件失败", e);
        }
    }

    // ---------- Facts ----------

    /** 获取某项目可见的记忆条目（全局 + 项目级）。 */
    public synchronized List<Fact> getFacts(String projectId) {
        List<Fact> all = new ArrayList<>();
        List<Fact> global = facts.get(GLOBAL);
        if (global != null) all.addAll(global);
        if (projectId != null && !projectId.isEmpty()) {
            List<Fact> proj = facts.get(projectId);
            if (proj != null) all.addAll(proj);
        }
        return all;
    }

    public synchronized void addFact(String projectId, String content) {
        if (content == null || content.isBlank()) return;
        Fact f = new Fact();
        f.id = UUID.randomUUID().toString();
        f.projectId = (projectId == null || projectId.isEmpty()) ? GLOBAL : projectId;
        f.content = content.trim();
        f.createdAt = System.currentTimeMillis();
        facts.computeIfAbsent(f.projectId, k -> new ArrayList<>()).add(f);
        save();
    }

    public synchronized void removeFact(String id) {
        for (List<Fact> list : facts.values()) {
            list.removeIf(f -> f.id.equals(id));
        }
        save();
    }

    public synchronized void clearFacts(String projectId) {
        if (projectId == null || projectId.isEmpty()) {
            facts.clear();
        } else {
            facts.remove(projectId);
        }
        save();
    }

    // ---------- Conversations ----------

    private static final int MAX_EXCHANGES = 20;

    public synchronized void appendConversation(String projectId, String role, String content) {
        if (content == null || content.isBlank()) return;
        String key = projectId == null ? GLOBAL : projectId;
        List<Exchange> list = conversations.computeIfAbsent(key, k -> new ArrayList<>());
        Exchange ex = new Exchange();
        ex.role = role;
        ex.content = content.length() > 4000 ? content.substring(0, 4000) : content;
        list.add(ex);
        while (list.size() > MAX_EXCHANGES) {
            list.remove(0);
        }
        save();
    }

    /** 渲染最近 n 轮对话为文本，注入提示词。 */
    public synchronized String renderRecentConversation(String projectId, int n) {
        List<Exchange> list = conversations.get(projectId == null ? GLOBAL : projectId);
        if (list == null || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, list.size() - n * 2);
        for (int i = from; i < list.size(); i++) {
            Exchange ex = list.get(i);
            String label = "user".equals(ex.role) ? "用户" : "助手";
            sb.append(label).append(": ").append(ex.content).append('\n');
        }
        return sb.toString();
    }

    /** 获取最近 n 条对话记录（设置面板展示用）。 */
    public synchronized List<Exchange> getRecentExchanges(String projectId, int n) {
        List<Exchange> list = conversations.get(projectId == null ? GLOBAL : projectId);
        if (list == null || list.isEmpty()) return new ArrayList<>();
        int from = Math.max(0, list.size() - n);
        return new ArrayList<>(list.subList(from, list.size()));
    }

    public synchronized void clearConversation(String projectId) {
        if (projectId == null || projectId.isEmpty()) {
            conversations.clear();
        } else {
            conversations.remove(projectId);
        }
        save();
    }
}
