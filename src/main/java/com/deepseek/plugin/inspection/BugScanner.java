package com.deepseek.plugin.inspection;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.EffectType;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.util.TextRange;
import com.intellij.ui.JBColor;

import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * Bug 扫描结果解析与编辑器标注。
 */
public final class BugScanner {

    private BugScanner() {
    }

    public static class BugIssue {
        public int line;             // 1-based
        public String severity;      // error / warning
        public String description;
        public String suggestion;

        @Override
        public String toString() {
            return "行 " + line + " [" + ("error".equalsIgnoreCase(severity) ? "错误" : "警告") + "] "
                    + description;
        }
    }

    /** 从回复文本中提取 JSON 数组并解析为问题列表。 */
    public static List<BugIssue> parse(String reply) {
        List<BugIssue> issues = new ArrayList<>();
        if (reply == null || reply.isBlank()) return issues;
        int start = reply.indexOf('[');
        int end = reply.lastIndexOf(']');
        if (start < 0 || end <= start) return issues;
        try {
            JsonArray arr = JsonParser.parseString(reply.substring(start, end + 1)).getAsJsonArray();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                BugIssue issue = new BugIssue();
                issue.line = o.has("line") ? o.get("line").getAsInt() : 0;
                issue.severity = o.has("severity") ? o.get("severity").getAsString() : "warning";
                issue.description = o.has("description") ? o.get("description").getAsString() : "";
                issue.suggestion = o.has("suggestion") ? o.get("suggestion").getAsString() : "";
                if (issue.line > 0) {
                    issues.add(issue);
                }
            }
        } catch (Exception ignore) {
            // 解析失败则不做标注
        }
        return issues;
    }

    /** 把问题标注到编辑器对应行，返回标注句柄用于清除。 */
    public static List<RangeHighlighter> applyHighlights(Editor editor, List<BugIssue> issues) {
        List<RangeHighlighter> highlighters = new ArrayList<>();
        Document doc = editor.getDocument();
        MarkupModel model = editor.getMarkupModel();
        for (BugIssue issue : issues) {
            int line = Math.min(issue.line, doc.getLineCount()) - 1;
            if (line < 0) continue;
            int lineStart = doc.getLineStartOffset(line);
            int lineEnd = doc.getLineEndOffset(line);
            TextRange range = new TextRange(lineStart, Math.max(lineStart + 1, lineEnd));
            TextAttributes attrs = new TextAttributes();
            attrs.setFontType(Font.PLAIN);
            if ("error".equalsIgnoreCase(issue.severity)) {
                attrs.setErrorStripeColor(JBColor.RED);
                attrs.setEffectColor(JBColor.RED);
                attrs.setEffectType(EffectType.WAVE_UNDERSCORE);
            } else {
                attrs.setErrorStripeColor(JBColor.YELLOW);
                attrs.setEffectColor(JBColor.YELLOW);
                attrs.setEffectType(EffectType.WAVE_UNDERSCORE);
            }
            RangeHighlighter h = model.addRangeHighlighter(
                    range.getStartOffset(), range.getEndOffset(),
                    HighlighterLayer.WARNING, attrs, HighlighterTargetArea.EXACT_RANGE);
            highlighters.add(h);
        }
        return highlighters;
    }

    public static void clearHighlights(Editor editor, List<RangeHighlighter> highlighters) {
        if (editor == null || editor.isDisposed()) return;
        MarkupModel model = editor.getMarkupModel();
        for (RangeHighlighter h : highlighters) {
            if (h != null && h.isValid()) {
                model.removeHighlighter(h);
            }
        }
        highlighters.clear();
    }
}
