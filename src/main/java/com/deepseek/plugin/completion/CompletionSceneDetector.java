package com.deepseek.plugin.completion;

import com.deepseek.plugin.context.CodeContextCollector.EditorContext;

/**
 * 场景识别器：根据 PSI 感知信息 + 光标上下文判断当前补全场景。
 * 优先级：PSI 精确匹配 > 语义意图 > 上下文统计 > 通用代码续写。
 * 含全局抑制规则（注释内/字符串拼接/import 中间/语法错误节点禁止触发）。
 */
public final class CompletionSceneDetector {

    private CompletionSceneDetector() {
    }

    /**
     * 识别当前场景。返回 NONE 表示不应触发补全。
     */
    public static CompletionScene detect(EditorContext ctx) {
        if (ctx == null) {
            return CompletionScene.NONE;
        }

        // ===== 全局抑制规则 =====
        if (shouldSuppress(ctx)) {
            return CompletionScene.NONE;
        }

        String before = ctx.beforeCaret == null ? "" : ctx.beforeCaret;
        String after = ctx.afterCaret == null ? "" : ctx.afterCaret;
        String line = ctx.caretLine == null ? currentLine(before) : ctx.caretLine;
        String enclosing = ctx.enclosingSignature == null ? "" : ctx.enclosingSignature;
        String comment = ctx.commentText == null ? "" : ctx.commentText;
        String prevComment = ctx.prevLineComment == null ? "" : ctx.prevLineComment;

        // ===== PSI 精确匹配（优先级最高）=====
        CompletionScene psiScene = detectByPsi(ctx, line, after, enclosing);
        if (psiScene != CompletionScene.NONE) {
            return psiScene;
        }

        // ===== 语义意图触发 =====
        // 注释转代码：PSI 识别在注释内（inComment=true），或上一行是注释且有内容
        if (ctx.inComment || isInCommentText(before, after)) {
            return CompletionScene.COMMENT_TO_CODE;
        }
        // 注释在上一行 + 当前行空白 + 注释含意图动词 → 注释转代码
        if (!prevComment.isBlank() && line.isBlank() && hasIntentVerb(prevComment)) {
            return CompletionScene.COMMENT_TO_CODE;
        }

        // ===== 结构化/上下文场景 =====
        // try-catch（语义意图）
        if (line.trim().startsWith("try") || trimEnd(before).endsWith("try")
                || line.trim().startsWith("catch")) {
            return CompletionScene.EXCEPTION_HANDLING;
        }
        // 正则：变量名/注释含匹配关键词，或在 Pattern.compile 中
        if (line.contains("Pattern.compile") || line.contains("matches(")
                || line.contains("replaceAll(") || line.toLowerCase().contains("regex")
                || line.contains("pattern")) {
            return CompletionScene.REGEX_BUILD;
        }
        // 链式调用（上下文统计）
        if (isChainCall(before)) {
            return CompletionScene.CHAIN_CALL_PREDICTION;
        }
        // 框架 API
        if (line.contains("mapper.") || line.contains("ServiceImpl")
                || line.contains("selectList") || line.contains("selectById")
                || line.contains("updateById") || line.contains("wrapper.")) {
            return CompletionScene.FRAMEWORK_API;
        }
        // 导入语句
        if (ctx.inImportArea || line.trim().startsWith("import ")) {
            return CompletionScene.IMPORT_SUGGESTION;
        }
        // SQL/Repository
        if (line.contains("@Select") || line.contains("@Insert")
                || line.contains("@Update") || line.contains("@Delete")
                || line.contains("BaseMapper") || line.contains("wrapper.")) {
            return CompletionScene.SQL_REPOSITORY;
        }
        // 配置键
        if (line.contains("@Value") || line.contains("${")) {
            return CompletionScene.CONFIG_KEY;
        }
        // 国际化
        if (line.contains("getMessage(") || line.contains("messageSource")
                || line.contains("i18n")) {
            return CompletionScene.I18N_KEY;
        }

        // ===== 默认：代码续写 =====
        return CompletionScene.CODE_CONTINUATION;
    }

    // ========== PSI 精确匹配 ==========

    private static CompletionScene detectByPsi(EditorContext ctx, String line, String after, String enclosing) {
        // 顶层区域（import/package/类声明前的空白）：任何代码生成场景都不适用，
        // 否则会在 import 区域生成方法/字段导致语法错误。统一交给后续 import 场景处理或抑制。
        if (ctx.inImportArea) {
            return CompletionScene.NONE;
        }
        // 样板代码：类名匹配 DTO/VO/Entity + 类体空 + 光标在类成员位置
        if (ctx.boilerplateType && (ctx.classBodyEmpty || line.isBlank() || line.startsWith("    "))) {
            return CompletionScene.BOILERPLATE;
        }
        // 接口/抽象方法实现
        if (ctx.hasUnimplementedMethods
                && (line.trim().startsWith("@Override") || line.isBlank() || line.startsWith("    "))) {
            return CompletionScene.IMPLEMENT_METHOD;
        }
        // 测试脚手架：仅当 PSI 确认光标在 @Test 方法内才触发。
        // 注意：不能用 enclosing.contains("Test") 判断——类名含 Test（如 TestBaseClue/TestUtil）
        // 会被误判，导致在普通方法体内生成完整测试类文件。
        if (ctx.inTestMethod) {
            // 方法体内空行 → 测试脚手架
            if (line.isBlank() || line.trim().startsWith("//")) {
                return CompletionScene.TEST_SKELETON;
            }
        }
        // 字符串字面量内
        if (ctx.inStringLiteral) {
            // JSON/XML：字符串包含结构字符
            if (line.contains("{") || line.contains("[") || line.contains("<")) {
                return CompletionScene.JSON_XML;
            }
            // 配置 ${ 内
            if (line.contains("${")) {
                return CompletionScene.CONFIG_KEY;
            }
        }
        return CompletionScene.NONE;
    }

    // ========== 抑制规则 ==========

    private static boolean shouldSuppress(EditorContext ctx) {
        // 1. import 语句中间（除 import 场景本身需要，这里指输入过程中的中间态）
        if (ctx.inImportArea && ctx.caretLine != null && !ctx.caretLine.trim().startsWith("import")) {
            return true;
        }
        // 2. 字符串拼接中间（a + b 的 + 号处）
        String line = ctx.caretLine == null ? "" : ctx.caretLine;
        if (line.trim().endsWith("+") && !ctx.inStringLiteral) {
            return true;
        }
        // 3. 注释内部但不是"注释转代码"意图（如行尾 // 只是半行、或 Javadoc 中）：
        //    这里不抑制——COMMENT_TO_CODE 场景由后续判断接管，避免误伤。
        return false;
    }

    // ========== 辅助 ==========

    private static boolean isInCommentText(String before, String after) {
        String line = currentLine(before);
        if (line.contains("//")) {
            return true;
        }
        int open = before.lastIndexOf("/*");
        if (open >= 0) {
            String tail = before.substring(open);
            if (!tail.contains("*/")) {
                return true;
            }
        }
        // 光标在块注释中间（before 有 /* 且 after 有 */）
        return before.contains("/*") && after.contains("*/");
    }

    private static String currentLine(String before) {
        int nl = before.lastIndexOf('\n');
        return nl >= 0 ? before.substring(nl + 1) : before;
    }

    private static boolean hasIntentVerb(String comment) {
        String c = comment.toLowerCase();
        String[] verbs = {"生成", "创建", "查询", "转换", "校验", "计算", "获取", "设置",
                "删除", "更新", "插入", "解析", "处理", "过滤", "统计", "排序", "实现",
                "保存", "发送", "接收", "调用", "组装", "构造"};
        for (String v : verbs) {
            if (c.contains(v)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isChainCall(String before) {
        String trimmed = trimEnd(before);
        if (!trimmed.endsWith(")")) {
            return false;
        }
        return trimmed.contains(".map(") || trimmed.contains(".filter(")
                || trimmed.contains(".collect(") || trimmed.contains(".builder()")
                || trimmed.contains(".stream()") || trimmed.contains("Builder")
                || trimmed.contains(".add(") || trimmed.contains(".set");
    }

    /** Java 8 兼容的 trimEnd（Java 11 的 String.trimEnd 不可用）。 */
    private static String trimEnd(String s) {
        return s == null ? "" : s.replaceAll("\\s+$", "");
    }
}
