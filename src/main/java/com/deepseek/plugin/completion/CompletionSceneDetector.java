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

        // ===== 注解补全：正在输入 @xxx（line 以 @ 开头）=====
        // 用户输入 @Aut / @Val 等半截注解时，期望补全注解名（Autowired/Value/...），
        // 而不是被默认续写场景引导去输出方法/字段主体。
        if (line.trim().startsWith("@")) {
            return CompletionScene.ANNOTATION;
        }

        // ===== 成员声明区补全 =====
        // 光标在类体内但不在方法内（enclosingSignature 只有"类:"没有"方法:"），
        // 且位于字段声明/注解之后的空行或行首 —— 期望补全注解+字段（如 @Autowired 注入），
        // 而不是输出方法。典型：已有 private FileUtil fileUtil; 后回车，期望下一行是 @Autowired。
        if (isMemberDeclarationPosition(ctx, before, line)) {
            return CompletionScene.MEMBER_DECLARATION;
        }

        // ===== 语义意图触发 =====
        // 注释转代码（收紧，修复"老是提示的不是我想要的代码"）：
        // 旧逻辑对"光标在注释内"（PSI inComment 或当前行含 //）一律触发，导致：
        // 1) 用户还在写注释（半截注释、URL/路径里的 //）时就弹出整段实现代码；
        // 2) 注释没有意图动词（如 "// 用户ID"）时模型凭空生成代码。
        // 现在只允许：光标已停在注释行尾（注释写完）+ 注释含意图动词 才触发。
        if ((ctx.inComment || isInCommentText(before, after))
                && isCaretAtLineEnd(after)
                && hasIntentVerb(currentLineComment(before))) {
            return CompletionScene.COMMENT_TO_CODE;
        }
        // 注释在上一行 + 当前行空白 + 注释含意图动词 → 注释转代码
        if (!prevComment.isBlank() && line.isBlank() && hasIntentVerb(prevComment)) {
            return CompletionScene.COMMENT_TO_CODE;
        }

        // ===== 结构化/上下文场景 =====
        // try-catch（语义意图）：必须整词匹配，避免变量名含子串误判
        // （country、tryCount、catchError 等标识符不应触发异常处理场景）。
        String tl = line.trim();
        if (tl.matches("try\\b.*") || tl.matches("catch\\b.*")
                || trimEnd(before).matches(".*\\btry\\s*$")
                || trimEnd(before).matches(".*\\bcatch\\s*$")) {
            return CompletionScene.EXCEPTION_HANDLING;
        }
        // 正则：仅在明确的 Pattern.compile / matches / replaceAll 调用中（去掉宽泛的 pattern 子串匹配，
        // 避免普通变量名含 pattern 就误触发）
        if (line.contains("Pattern.compile(") || line.contains("matches(")
                || line.contains("replaceAll(") || line.contains("Pattern.matches(")) {
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
        // 配置键：仅 @Value 行触发；裸 ${（字符串内）由 PSI 分支处理（且 ${ 必须先于 { 判断，
        // 否则 @Value("${app.x}") 会误命中 JSON_XML）
        if (line.contains("@Value")) {
            return CompletionScene.CONFIG_KEY;
        }
        // 国际化（\b 边界避免 i18nUtil 这类变量名误判）
        if (line.contains("getMessage(") || line.contains("messageSource")
                || line.matches(".*\\bi18n\\b.*")) {
            return CompletionScene.I18N_KEY;
        }

        // ===== 默认：代码续写（仅在明确的续写位置）=====
        // 平衡策略（2026-08-14 第二次调整）：
        // - 0.1.2 太灵敏：兜底 CODE_CONTINUATION 且无位置约束 → 任何地方回车/打点都弹；
        // - 0.1.3 太钝：兜底 NONE → 普通代码续写完全静默，用户觉得"半天没反应"；
        // - 0.1.4：恢复 CODE_CONTINUATION，但只在"语句结束位置"才续写
        //   （光标前以 ; } ) { : 结尾），写标识符/打点/括号中间不触发。
        //   配合 EnterToCompletionListener 的 500ms 停顿检测，连续输入不打扰。
        if (isContinuationPosition(before)) {
            return CompletionScene.CODE_CONTINUATION;
        }
        return CompletionScene.NONE;
    }

    /**
     * 是否处于可续写位置：光标前文本以语句结束符结尾。
     * 只有明确的"上一句已结束"才允许普通续写，避免打字过程中频繁弹建议。
     */
    private static boolean isContinuationPosition(String before) {
        String t = trimEnd(before == null ? "" : before);
        if (t.isEmpty()) {
            return false;
        }
        char last = t.charAt(t.length() - 1);
        return last == ';' || last == '}' || last == ')' || last == '{' || last == ':';
    }

    /**
     * 成员声明区判断：光标在类体内、不在方法内、不在 import 区。
     * 两种情况：
     * A. 光标在空行（刚回车），前一行是字段声明/注解 → 期望补全注解+字段；
     * B. 光标在字段声明行行尾（分号后）→ 期望补全下一个字段/注解，
     *    而不是被默认续写场景引导去输出表达式碎片/方法。
     */
    private static boolean isMemberDeclarationPosition(EditorContext ctx, String before, String line) {
        // 不在 import 区（含顶层空白区）
        if (ctx.inImportArea) {
            return false;
        }
        // 必须位于类成员位置：enclosingSignature 只有"类:"、没有"方法:"
        String enclosing = ctx.enclosingSignature == null ? "" : ctx.enclosingSignature;
        if (enclosing.isEmpty() || enclosing.contains("方法:")) {
            return false;
        }
        // 情况 A：当前行为空/仅空白（用户刚回车，准备写新成员）
        if (line.trim().isEmpty()) {
            String prev = prevNonBlankLine(before);
            if (prev.isEmpty()) {
                return false;
            }
            // 上一行是字段声明（以 ; 结尾且不是方法调用/签名）或注解行 → 成员区
            if (isFieldDeclarationLine(prev) || prev.startsWith("@")) {
                return true;
            }
            return false;
        }
        // 情况 B：当前行是字段声明行（caretLine 含缩进，需 trim 判断），
        // 且光标在行尾（光标前以 ; 结尾）→ 成员区，补全下一个字段/注解
        if (isFieldDeclarationLine(line.trim()) && trimEnd(before).endsWith(";")) {
            return true;
        }
        return false;
    }

    /** 光标前最后一个非空行（不含当前行）。 */
    private static String prevNonBlankLine(String before) {
        if (before == null || before.isEmpty()) {
            return "";
        }
        String[] lines = before.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String t = lines[i].trim();
            if (!t.isEmpty()) {
                return t;
            }
        }
        return "";
    }

    /**
     * 字段声明行判断：以分号结尾，且不含括号（排除方法签名/方法调用/if-for 等语句）。
     * 如 "private FileUtil fileUtil;"、"@Value(\"${x}\") private String y;" 的字段部分
     * （注解行单独以 @ 判断）。空类体场景的 "public class Foo {" 不会被误判。
     */
    private static boolean isFieldDeclarationLine(String line) {
        if (line == null || !line.endsWith(";")) {
            return false;
        }
        // 含 ( 视为方法签名/调用/控制语句，不是字段声明
        if (line.contains("(") || line.contains(")")) {
            return false;
        }
        // 字段声明必须含访问修饰符/类型关键字之一
        return line.startsWith("private") || line.startsWith("public")
                || line.startsWith("protected") || line.startsWith("static")
                || line.startsWith("final") || line.startsWith("volatile")
                || line.startsWith("transient");
    }

    // ========== PSI 精确匹配 ==========

    private static CompletionScene detectByPsi(EditorContext ctx, String line, String after, String enclosing) {
        // 顶层区域（import/package/类声明前的空白）：任何代码生成场景都不适用，
        // 否则会在 import 区域生成方法/字段导致语法错误。统一交给后续 import 场景处理或抑制。
        if (ctx.inImportArea) {
            return CompletionScene.NONE;
        }
        // 样板代码：类名匹配 DTO/VO/Entity + 类体空/成员位置。
        // 必须不在方法内（enclosing 不含"方法:"）：旧逻辑对 DTO 类方法体内的任何
        // 空白行/缩进行都返回 BOILERPLATE，用户在 DTO/VO/Entity/Req 类里写业务方法时
        // 停顿就弹出"字段/Getter/Setter"，是"提示的不是我想要的代码"的头号来源。
        if (ctx.boilerplateType && !enclosing.contains("方法:")
                && (ctx.classBodyEmpty || line.isBlank() || line.startsWith("    "))) {
            return CompletionScene.BOILERPLATE;
        }
        // 接口/抽象方法实现：只在明确位置触发——
        // a) 光标在 @Override 行；
        // b) 空方法体（方法签名后刚回车，光标后是方法闭合 }）。
        // 不再用 line.startsWith("    ") 全缩进行匹配，避免类内有未实现方法时任何普通代码都弹"生成实现"。
        if (ctx.hasUnimplementedMethods && line.trim().startsWith("@Override")) {
            return CompletionScene.IMPLEMENT_METHOD;
        }
        if (ctx.hasUnimplementedMethods && line.isBlank() && after.trim().startsWith("}")) {
            return CompletionScene.IMPLEMENT_METHOD;
        }
        // 测试脚手架：仅 PSI 确认光标在 @Test 方法内，且是空方法体或带意图的注释行才触发。
        // 收紧：@Test 方法体内的普通空行不再弹"新测试方法"，避免打字时被脚手架建议打扰。
        if (ctx.inTestMethod) {
            if (line.isBlank() && after.trim().startsWith("}")) {
                return CompletionScene.TEST_SKELETON;
            }
            String prevLineComment = ctx.prevLineComment == null ? "" : ctx.prevLineComment;
            if (line.trim().startsWith("//") && hasIntentVerb(prevLineComment)) {
                return CompletionScene.TEST_SKELETON;
            }
        }
        // 字符串字面量内
        if (ctx.inStringLiteral) {
            // 配置 ${ 内（必须先于 JSON/XML，因为 ${ 也含 {）
            if (line.contains("${")) {
                return CompletionScene.CONFIG_KEY;
            }
            // JSON/XML：仅当光标所在行内存在未闭合的 { / [，或 < 后紧跟字母（XML 标签起始）。
            // 避免 "价格 < 100" 这类普通文本误触发 XML 补全。
            if (looksLikeJsonOrXmlStart(line)) {
                return CompletionScene.JSON_XML;
            }
        }
        return CompletionScene.NONE;
    }

    /** 当前行内是否处于 JSON/XML 结构起始：未闭合的 { / [，或 < 后紧跟字母。 */
    private static boolean looksLikeJsonOrXmlStart(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        int openBrace = line.length() - line.replace("{", "").length();
        int closeBrace = line.length() - line.replace("}", "").length();
        int openBracket = line.length() - line.replace("[", "").length();
        int closeBracket = line.length() - line.replace("]", "").length();
        if (openBrace > closeBrace || openBracket > closeBracket) {
            return true;
        }
        int lt = line.lastIndexOf('<');
        return lt >= 0 && lt + 1 < line.length() && Character.isLetter(line.charAt(lt + 1));
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
        // 3. Javadoc（/** 未闭合的块注释）内：写的是文档描述，不应生成代码。
        //    行注释 // 与普通块注释 /*（带意图动词）仍走 COMMENT_TO_CODE。
        String before = ctx.beforeCaret == null ? "" : ctx.beforeCaret;
        String after = ctx.afterCaret == null ? "" : ctx.afterCaret;
        if (isInJavadoc(before, after)) {
            return true;
        }
        return false;
    }

    /** 是否位于 Javadoc 注释内：光标前有未闭合的 /**，或光标在 Javadoc 中间（前后各有一段）。 */
    private static boolean isInJavadoc(String before, String after) {
        int javadocOpen = before.lastIndexOf("/**");
        int blockClose = before.lastIndexOf("*/");
        if (javadocOpen >= 0 && javadocOpen > blockClose) {
            return true;
        }
        return before.contains("/**") && !before.contains("*/") && after.contains("*/");
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

    /** 当前行注释文本（// 或 /* 之后的内容），用于判断注释是否含意图动词。 */
    private static String currentLineComment(String before) {
        String line = currentLine(before);
        int idx = line.indexOf("//");
        if (idx >= 0) {
            return line.substring(idx + 2).trim();
        }
        int open = line.indexOf("/*");
        if (open >= 0) {
            return line.substring(open + 2).trim();
        }
        return "";
    }

    /** 光标是否位于行尾（光标后当前行无内容）——"注释已写完"的判定。 */
    private static boolean isCaretAtLineEnd(String after) {
        if (after == null || after.isEmpty()) {
            return true;
        }
        int nl = after.indexOf('\n');
        String rest = nl < 0 ? after : after.substring(0, nl);
        return rest.isBlank();
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
        // 链式调用位置：光标前以 ) 结尾（回车后）或以 . 结尾（刚打完点）都可能。
        // 不以 ) 或 . 结尾（如 dto.setAttrId( 括号中间）不算链式场景，避免打点就弹。
        if (!trimmed.endsWith(")") && !trimmed.endsWith(".")) {
            return false;
        }
        // 只看【当前行】：旧逻辑在整段 before（最多 4000 字符）里找 .map/.set 等标记，
        // 上文任意一处普通 setter 调用都会误判成链式场景，光标处却根本不是链式位置，
        // 模型被要求"只输出下一个链式方法"，结果弹出与上下文无关的碎片。
        int nl = trimmed.lastIndexOf('\n');
        String lastLine = nl < 0 ? trimmed : trimmed.substring(nl + 1);
        return lastLine.contains(".map(") || lastLine.contains(".filter(")
                || lastLine.contains(".collect(") || lastLine.contains(".builder()")
                || lastLine.contains(".stream()") || lastLine.contains("Builder")
                || lastLine.contains(".add(") || lastLine.contains(".set");
    }

    /** Java 8 兼容的 trimEnd（Java 11 的 String.trimEnd 不可用）。 */
    private static String trimEnd(String s) {
        return s == null ? "" : s.replaceAll("\\s+$", "");
    }
}
