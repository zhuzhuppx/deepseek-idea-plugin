package com.deepseek.plugin.prompt;

import com.deepseek.plugin.context.CodeContextCollector;
import com.deepseek.plugin.context.ProjectScanner;
import com.deepseek.plugin.memory.MemoryStore;
import com.deepseek.plugin.memory.MemoryStore.Fact;
import com.deepseek.plugin.settings.DeepSeekState;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;

import java.util.List;

/**
 * Java 专家模式提示词引擎。
 * system prompt = Java 专家人设 + 记忆（事实 + 近期对话）
 * user prompt = 具体任务 + 当前文件上下文 + 项目上下文（结构/依赖/相关文件）
 */
public final class JavaExpertPrompt {

    private JavaExpertPrompt() {
    }

    /** 构建 system prompt（人设 + 记忆）。 */
    public static String buildSystemPrompt(Project project) {
        DeepSeekState state = DeepSeekState.getInstance();
        StringBuilder sb = new StringBuilder();
        sb.append("你是「DeepSeek Java Expert」——一位资深 Java 专家，精通 Java 17、Spring Boot/MyBatis 等主流框架、");
        sb.append("设计模式与代码质量最佳实践。回答要求：准确、简洁、直接给出可用的 Java 代码；");
        sb.append("生成代码一律用 ```java 代码块包裹；除非用户要求，不要输出多余解释。\n");

        appendMemory(sb, state, project, true);
        return sb.toString();
    }

    /** 根据补全场景构建 user prompt。 */
    public static String buildScenePrompt(Project project, PsiFile file,
                                          com.deepseek.plugin.completion.CompletionScene scene,
                                          CodeContextCollector.EditorContext ctx) {
        return switch (scene) {
            case COMMENT_TO_CODE -> commentToCodePrompt(sceneComment(ctx), ctx);
            case CHAIN_CALL_PREDICTION -> chainCallPrompt(ctx);
            case BOILERPLATE -> boilerplatePrompt(ctx);
            case EXCEPTION_HANDLING -> exceptionHandlingPrompt(ctx);
            case TEST_SKELETON -> testSkeletonPrompt(ctx);
            case FRAMEWORK_API -> frameworkApiPrompt(ctx);
            case IMPLEMENT_METHOD -> implementMethodPrompt(ctx);
            case SQL_REPOSITORY -> sqlRepositoryPrompt(ctx);
            case IMPORT_SUGGESTION -> importSuggestionPrompt(ctx);
            case JSON_XML -> jsonXmlPrompt(ctx);
            case CONFIG_KEY -> configKeyPrompt(ctx);
            case REGEX_BUILD -> regexPrompt(ctx);
            case I18N_KEY -> i18nKeyPrompt(ctx);
            case ANNOTATION -> annotationPrompt(ctx);
            case MEMBER_DECLARATION -> memberDeclarationPrompt(ctx);
            default -> completionPrompt(project, file, ctx.filePath, ctx.importsSummary,
                    ctx.enclosingSignature, ctx.beforeCaret, ctx.afterCaret);
        };
    }

    /** 场景识别时的注释内容（供 COMMENT_TO_CODE 使用）。 */
    public static String sceneComment(CodeContextCollector.EditorContext ctx) {
        String comment = ctx.commentText;
        if (comment == null || comment.isBlank()) {
            // 从 beforeCaret 提取 // 或 /* 内容
            String before = ctx.beforeCaret == null ? "" : ctx.beforeCaret;
            int idx = before.lastIndexOf("//");
            if (idx >= 0 && before.indexOf('\n', idx) < 0) {
                return before.substring(idx + 2).trim();
            }
            int open = before.lastIndexOf("/*");
            if (open >= 0) {
                String tail = before.substring(open + 2);
                int close = tail.indexOf("*/");
                if (close >= 0) tail = tail.substring(0, close);
                return tail.trim();
            }
        }
        return comment == null ? "" : comment.replace("/*", "").replace("*/", "").replace("*", "").trim();
    }

    /** 链式调用预测。 */
    private static String chainCallPrompt(CodeContextCollector.EditorContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：Java 链式调用补全。根据当前 Builder/Stream 链的返回类型和历史用法，预测下一个最可能的方法。\n");
        sb.append("【光标前代码】\n").append(ctx.beforeCaret).append('\n');
        sb.append("\n要求：1. 只输出下一个链式方法调用；若光标前已输入 .（如 xxx.stream(). 之后），"
                + "只输出方法名与参数（如 filter(...)），不要重复输出点；若光标前没有点，才输出带 . 的调用（如 .filter(...)）；\n");
        sb.append("2. 基于返回类型推断合法方法，不输出不存在的 API；\n3. 风格与已有链一致。");
        return sb.toString();
    }

    /** 样板代码生成。 */
    private static String boilerplatePrompt(CodeContextCollector.EditorContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：Java 样板代码生成。在类体内光标处生成合理的字段、Getter/Setter、Builder 或构造方法。\n");
        sb.append("【光标前代码】\n").append(ctx.beforeCaret).append('\n');
        if (ctx.importsSummary != null && !ctx.importsSummary.isBlank()) {
            sb.append("【已有导入】\n").append(ctx.importsSummary).append('\n');
        }
        sb.append("\n要求：1. 只输出代码本身，不要解释；2. 风格与已有字段/方法一致；");
        sb.append("3. 根据上下文推断实体字段；4. 不要重复已有内容。");
        return sb.toString();
    }

    /** 智能异常处理。 */
    private static String exceptionHandlingPrompt(CodeContextCollector.EditorContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：Java 异常处理补全。补全 try-catch 块，填充符合项目规范的日志记录、异常转换及资源关闭逻辑。\n");
        sb.append("【光标前代码】\n").append(ctx.beforeCaret).append('\n');
        if (ctx.enclosingSignature != null && !ctx.enclosingSignature.isBlank()) {
            sb.append("【所在位置】\n").append(ctx.enclosingSignature).append('\n');
        }
        sb.append("\n要求：1. 只输出代码；2. 使用 slf4j 日志风格；3. 遵循项目已有异常处理惯例。");
        return sb.toString();
    }

    /** 单元测试脚手架。 */
    private static String testSkeletonPrompt(CodeContextCollector.EditorContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：Java 单元测试脚手架生成。根据被测方法签名，生成 Given-When-Then 结构的测试代码（BDD 风格）与 Mock 设置。\n");
        sb.append("【光标前代码】\n").append(ctx.beforeCaret).append('\n');
        if (ctx.enclosingSignature != null && !ctx.enclosingSignature.isBlank()) {
            sb.append("【被测目标】\n").append(ctx.enclosingSignature).append('\n');
        }
        sb.append("\n要求：1. 只输出测试代码（JUnit 5 + Mockito）；2. 使用 given/when/then 结构；");
        sb.append("3. 覆盖正常路径与关键边界；4. 保持简洁；");
        sb.append("5. 严禁输出与上文重复的语句行（如连续多行相同表达式），严禁机械模仿上文已有的调用模式。\n");
        sb.append("重要：光标位于既有测试类的方法体内，严禁输出 package 声明、import 语句、");
        sb.append("class/interface 声明或任何文件级结构，只输出方法体内的测试语句。");
        sb.append("若被测目标不明确（如空方法体、方法名无业务含义、无法推断测试意图），");
        sb.append("不要凭空捏造代码，直接输出空内容。");
        return sb.toString();
    }

    /** 框架 API 惯用法。 */
    private static String frameworkApiPrompt(CodeContextCollector.EditorContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：Java 框架 API 惯用法补全。识别 Spring/MyBatis-Plus 等框架用法，提供符合当前项目规范的 API 调用链。\n");
        sb.append("【光标前代码】\n").append(ctx.beforeCaret).append('\n');
        if (ctx.importsSummary != null && !ctx.importsSummary.isBlank()) {
            sb.append("【已有导入】\n").append(ctx.importsSummary).append('\n');
        }
        sb.append("\n要求：1. 只输出代码；若光标前已输入点（如 mapper. 或 wrapper. 之后），"
                + "只输出方法名与参数（如 selectList(...)），不要重复输出点；\n");
        sb.append("2. 使用项目当前框架版本对应的 API；3. 不输出已导入以外的魔法方法。");
        return sb.toString();
    }

    /** 接口/抽象方法实现。 */
    private static String implementMethodPrompt(CodeContextCollector.EditorContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：实现接口/抽象方法。生成方法体，保留参数名与泛型约束。\n");
        sb.append("【光标前代码】\n").append(ctx.beforeCaret).append('\n');
        if (ctx.enclosingSignature != null && !ctx.enclosingSignature.isBlank()) {
            sb.append("【所在位置】\n").append(ctx.enclosingSignature).append('\n');
        }
        sb.append("\n要求：1. 若光标前只有 @Override（或空行/缩进处），输出完整方法签名与方法体"
                + "（如 public void foo(...) { ... }；@Override 已存在则从方法签名开始）；"
                + "若光标前已有方法签名，只输出方法体；\n");
        sb.append("2. 返回合理默认值或基于上下文实现；3. 保留 throws 与泛型；"
                + "4. 严禁输出 package/import/class 等文件级结构。");
        return sb.toString();
    }

    /** SQL/Repository 辅助。 */
    private static String sqlRepositoryPrompt(CodeContextCollector.EditorContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：SQL/Repository 辅助补全。在 Mapper 注解或 Query 方法中，基于实体映射补全列名、条件及参数占位符。\n");
        sb.append("【光标前代码】\n").append(ctx.beforeCaret).append('\n');
        if (ctx.importsSummary != null && !ctx.importsSummary.isBlank()) {
            sb.append("【已有导入】\n").append(ctx.importsSummary).append('\n');
        }
        sb.append("\n要求：1. 只输出代码/SQL；2. 列名与实体字段映射一致（驼峰转下划线）；3. 使用 ? 或 #{} 占位符。");
        return sb.toString();
    }

    /** 导入语句补全。 */
    private static String importSuggestionPrompt(CodeContextCollector.EditorContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：Java 导入语句补全。当前光标位于 import 区域（package 与类声明之间），"
                + "请根据代码中使用的类名/注解推断正确的 import 路径（注意同名类区分）。\n");
        sb.append("【光标前代码】\n").append(ctx.beforeCaret).append('\n');
        sb.append("\n要求：1. 只输出 import 语句本身，严禁输出方法、类声明、字段、语句块、注释或任何其他代码；\n");
        sb.append("2. 若光标前已有不完整的 import（如 \"import java.util.\"），只输出剩余部分（如 \"List;\"）；\n");
        sb.append("3. 区分同名类选常用包；4. 使用已出现的包前缀；5. 以分号结尾。");
        return sb.toString();
    }

    /** JSON/XML 结构补全。 */
    private static String jsonXmlPrompt(CodeContextCollector.EditorContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：JSON/XML 结构补全。根据目标反序列化类或上下文，补全完整的 JSON/XML 模板。\n");
        sb.append("【光标前代码】\n").append(ctx.beforeCaret).append('\n');
        sb.append("\n要求：1. 只输出 JSON/XML 内容；2. 字段与目标类属性对应；3. 保持合法格式。");
        return sb.toString();
    }

    /** 配置键值绑定。 */
    private static String configKeyPrompt(CodeContextCollector.EditorContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：Spring 配置键补全。在 @Value 或配置引用中，基于常见配置结构补全 Key，并提示类型与默认值。\n");
        sb.append("【光标前代码】\n").append(ctx.beforeCaret).append('\n');
        sb.append("\n要求：1. 只输出配置 key（如 app.xxx.yyy）；2. 使用合理命名；3. 不输出无关内容。");
        return sb.toString();
    }

    /** 正则表达式构建。 */
    private static String regexPrompt(CodeContextCollector.EditorContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：正则表达式构建。根据描述或示例字符串，生成与上下文意图匹配的正则表达式并正确转义。\n");
        sb.append("【光标前代码】\n").append(ctx.beforeCaret).append('\n');
        sb.append("\n要求：1. 只输出正则字符串；2. 特殊字符正确转义；3. 与上下文意图匹配。");
        return sb.toString();
    }

    /** 国际化键补全。 */
    private static String i18nKeyPrompt(CodeContextCollector.EditorContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：国际化 Message Key 补全。基于常见 i18n 资源命名规范，补全 Message Key。\n");
        sb.append("【光标前代码】\n").append(ctx.beforeCaret).append('\n');
        sb.append("\n要求：1. 只输出 key（如 error.user.notFound）；2. 遵循资源文件层级；3. 语义准确。");
        return sb.toString();
    }

    /** 注解补全：光标在 @ 后输入中，补全注解名。 */
    private static String annotationPrompt(CodeContextCollector.EditorContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：Java 注解补全。光标位于注解输入中（已输入 @）。\n");
        sb.append("【光标前代码】\n").append(ctx.beforeCaret).append('\n');
        if (ctx.enclosingSignature != null && !ctx.enclosingSignature.isBlank()) {
            sb.append("【所在位置】\n").append(ctx.enclosingSignature).append('\n');
        }
        sb.append("\n要求：1. 只输出注解名及必要参数（如 Autowired、Value(\"${...}\")、Override、Resource、Test、Data 等）；\n");
        sb.append("2. 根据上下文（Spring/MyBatis/JUnit/Jackson）推断最可能的注解；\n");
        sb.append("3. 若光标前已有半截注解（如 \"@Aut\"），从已有前缀继续补全，不要重复 @ 符号；\n");
        sb.append("4. 严禁输出方法、类、字段声明、语句或任何非注解内容。");
        return sb.toString();
    }

    /** 成员声明区补全：光标在类成员区（字段/注解位置），补全注解+字段声明。 */
    private static String memberDeclarationPrompt(CodeContextCollector.EditorContext ctx) {
        // 只有项目真实导入过 Spring 注解才提示 @Autowired；非 Spring 项目强推
        // @Autowired 会生成无法编译的字段（"提示的不是我想要的代码"）。
        String imports = ctx.importsSummary == null ? "" : ctx.importsSummary;
        boolean springProject = imports.contains("org.springframework.beans.factory.annotation")
                || imports.contains("org.springframework.context.annotation")
                || imports.contains("javax.inject") || imports.contains("jakarta.inject");
        StringBuilder sb = new StringBuilder();
        sb.append("任务：Java 类成员补全。光标位于类成员声明区（字段/注解位置），"
                + "根据项目上下文补全下一个字段声明及其注解");
        if (springProject) {
            sb.append("（Spring 注入场景优先 @Autowired/@Resource）");
        }
        sb.append("。\n");
        sb.append("【光标前代码】\n").append(ctx.beforeCaret).append('\n');
        if (!imports.isBlank()) {
            sb.append("【已有导入】\n").append(imports).append('\n');
        }
        if (ctx.enclosingSignature != null && !ctx.enclosingSignature.isBlank()) {
            sb.append("【所在位置】\n").append(ctx.enclosingSignature).append('\n');
        }
        sb.append("\n要求：1. 只输出字段声明及其注解（如 private FileUtil fileUtil;），或仅注解；\n");
        sb.append("2. 根据上下文（类名、已有字段、导入）推断需要注入的依赖与字段类型、命名；\n");
        sb.append("3. 注解风格与已有字段一致");
        if (springProject) {
            sb.append("（Spring 项目优先 @Autowired/@Resource，配置类用 @Value 等）");
        } else {
            sb.append("；若导入中没有任何 Spring/注入注解，不要凭空输出 @Autowired 等注解，只输出字段声明");
        }
        sb.append("；\n4. 严禁输出方法、方法体、类声明或任何非字段内容。");
        return sb.toString();
    }

    /** 行内补全专用的 system prompt：人设与记忆相同，但不要求代码块包裹（补全只输出裸代码）。 */
    public static String buildCompletionSystemPrompt(Project project) {
        DeepSeekState state = DeepSeekState.getInstance();
        StringBuilder sb = new StringBuilder();
        sb.append("你是「DeepSeek Java Expert」——一位资深 Java 专家，精通 Java 17、Spring Boot/MyBatis 等主流框架、");
        sb.append("设计模式与代码质量最佳实践。你正在 IDE 中提供行内代码补全：");
        sb.append("只输出纯代码文本，不要 ```java 代码块标记、不要解释、不要任何多余字符；");
        sb.append("输出内容将直接插入光标位置。");
        sb.append("牢记：这是行内补全，只输出光标之后新增的代码片段，");
        sb.append("严禁把提示词中已有的代码原样重复输出（那会导致从已有代码开始覆盖，变成重写而不是补全）。");
        sb.append("输出必须完整：字符串字面量、括号、语句分号都必须闭合，不要输出半截语句；");
        sb.append("只基于【光标前代码】补全代码，严禁把用户对话内容、评论文本或无关文字编造进代码里。\n");

        // 行内补全不带"近期对话记录"：里面是历史补全的助手回复，模型会模仿旧建议（2026-08-14 修复）
        appendMemory(sb, state, project, false);
        return sb.toString();
    }

    private static void appendMemory(StringBuilder sb, DeepSeekState state, Project project, boolean includeConversation) {
        if (state.memoryEnabled && project != null) {
            String projectId = project.getBasePath();
            List<Fact> facts = MemoryStore.getInstance().getFacts(projectId);
            if (!facts.isEmpty()) {
                sb.append("\n【记忆条目（可能过时，仅供参考，若与当前代码冲突以代码为准）】\n");
                for (Fact f : facts) {
                    sb.append("- ").append(f.content).append('\n');
                }
            }
            // 近期对话只注入聊天类请求（解释/优化/测试/扫Bug）。
            // 行内补全不注入：对话记录里含历史补全结果，模型会机械模仿旧建议，
            // 造成"老是提示同一类/同一段代码"（2026-08-14 修复）。
            if (includeConversation) {
                String conv = MemoryStore.getInstance().renderRecentConversation(projectId, state.memoryRecentExchanges);
                if (!conv.isBlank()) {
                    sb.append("\n【近期对话记录（用于保持上下文连贯）】\n").append(conv);
                }
            }
        }
    }

    /** 项目上下文段落（结构 + 依赖 + 相关文件），受设置开关控制。 */
    public static String buildProjectContext(Project project, PsiFile currentFile) {
        DeepSeekState state = DeepSeekState.getInstance();
        if (!state.projectContextEnabled || project == null || project.isDisposed()) {
            return "";
        }
        return ProjectScanner.buildProjectContext(project, currentFile,
                state.contextMaxRelatedFiles, state.contextMaxFileChars);
    }

    /** 行内补全提示词。before/after 为光标前后代码。 */
    public static String completionPrompt(Project project, PsiFile file, String filePath, String imports,
                                          String enclosing, String before, String after) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：Java 代码行内补全。光标位置用 【▼ 光标（空白）▼】 标记，请只补全该空白处应填入的代码。\n");
        if (filePath != null && !filePath.isEmpty()) sb.append("【文件】").append(filePath).append('\n');
        if (imports != null && !imports.isBlank()) sb.append("【导入】\n").append(imports).append('\n');
        if (enclosing != null && !enclosing.isBlank()) sb.append("【所在位置】\n").append(enclosing).append('\n');
        // 就近裁剪：光标前只保留最近 2500 字符（更早代码对补全几乎无影响，且拖慢响应）
        String recentBefore = trimHead(before, 2500, "// ...(更早代码省略)");
        sb.append("【光标前代码】\n").append(recentBefore).append('\n');
        sb.append("【▼ 光标位置（空白，只补全这里）▼】\n");
        String recentAfter = after == null ? "" : (after.length() > 800 ? after.substring(0, 800) + "\n// ...(更后代码省略)" : after);
        if (!recentAfter.isBlank()) sb.append("【光标后代码】\n").append(recentAfter).append('\n');
        // 轻量项目上下文：相关源码文件（import 引用的类）——让模型知道项目里真实存在的类/字段/方法
        String projCtx = buildLightProjectContext(project, file);
        if (!projCtx.isBlank()) sb.append("\n【项目相关文件（参考，勿重复输出其中内容）】\n").append(projCtx).append('\n');
        sb.append("\n请只输出光标空白处应当补全的代码。");
        sb.append("要求：\n1. 只输出代码本身，不要解释、不要包裹代码块；");
        sb.append("空白前后代码只是上下文——严禁把其中已存在的任何内容重复输出到空白里，");
        sb.append("也不要从空白之前的已有代码开始重写，只输出光标之后新增的部分；\n");
        sb.append("2. 与已有代码风格一致，变量名贴合上下文；\n3. 补全内容必须与空白后的代码自然衔接；\n");
        sb.append("4. 严禁输出多行内容完全相同的重复语句（如 event; 连续出现三行）——那没有任何意义；"
                + "若上文已有连续同模式调用（如多个 eventPublisher.publishEvent(...)），"
                + "只有新内容有明确差异（新事件对象、新参数、新方法名）时才续写，否则输出空内容；\n");
        sb.append("5. 若光标前刚输入的是一段标识符前缀（如 eve/event），应补全为上下文中已出现的完整标识符"
                + "（如 eventPublisher）并自然续写，或基于该前缀输出一句语义完整的新代码；"
                + "严禁把前缀补成孤立的分号、无意义重复行或残缺语句；\n");
        sb.append("6. 若光标位于方法/语句结束后（后续没有明确续写点），则续写该类下一个合理的方法或字段，");
        sb.append("让代码自然延续；若确实没有合理内容可补全，输出空内容。");
        return sb.toString();
    }

    /** 取文本尾部 maxChars 字符，尽量从行首切（避免从行中间截断）；超长时加省略标记。 */
    private static String trimHead(String text, int maxChars, String prefix) {
        if (text == null || text.length() <= maxChars) return text == null ? "" : text;
        String tail = text.substring(text.length() - maxChars);
        int nl = tail.indexOf('\n');
        String cut = nl >= 0 ? tail.substring(nl + 1) : tail;
        return prefix + "\n" + cut;
    }

    /** 行内补全专用轻量项目上下文：仅取 import 引用的相关类文件，严格控制体积避免拖慢响应。 */
    private static String buildLightProjectContext(Project project, PsiFile file) {
        try {
            if (project == null || project.isDisposed() || file == null) return "";
            DeepSeekState state = DeepSeekState.getInstance();
            if (!state.projectContextEnabled) return "";
            // 相关文件最多 2 个、每文件 2000 字符（复用 30s 缓存）
            java.util.Map<String, String> related = ProjectScanner.collectRelatedFiles(project, file, 2, 2000);
            if (related.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, String> e : related.entrySet()) {
                sb.append("===== ").append(e.getKey()).append(" =====\n").append(e.getValue()).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 解释代码 */
    public static String explainPrompt(String code) {
        return "请解释下面这段 Java 代码：它的作用、关键逻辑、潜在问题。用中文回答，简洁清晰。\n```java\n"
                + code + "\n```";
    }

    /** 优化重构 */
    public static String optimizePrompt(String code) {
        return "请分析下面这段 Java 代码，给出优化/重构建议：指出可改进点（性能、可读性、健壮性、命名、设计模式），"
                + "并给出重构后的完整代码（用 ```java 代码块包裹）。\n```java\n" + code + "\n```";
    }

    /** 生成单元测试 */
    public static String generateTestPrompt(String code) {
        return "为下面这段 Java 代码生成 JUnit 5 单元测试：覆盖主要逻辑与边界情况，"
                + "包含必要的 import 与断言，使用 Mockito 时注明。只输出测试代码，用 ```java 代码块包裹。\n```java\n"
                + code + "\n```";
    }

    /** 注释文字补全：光标在注释块/行内，续写注释内容（不是生成代码）。 */
    public static String commentCompletionPrompt(String before, String after) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：Java 代码注释补全。光标位于注释内部。\n");
        // 只取光标前最近的部分（含注释标记），突出注释要描述的代码
        String recent = before.length() > 1500 ? before.substring(before.length() - 1500) : before;
        sb.append("【注释前的代码（注释应描述这段代码的用途）】\n").append(recent).append('\n');
        if (!after.isBlank()) sb.append("【光标后内容】\n").append(after).append('\n');
        sb.append("\n请只输出光标位置之后应当补全的注释文字：\n");
        sb.append("1. 只输出注释文本本身，不要 /* */ 或 // 标记（若光标后已有注释结束符则自动衔接）；\n");
        sb.append("2. 用简洁中文描述补全内容，与已有注释风格一致；\n");
        sb.append("3. 若光标前注释为空（刚输入 // 或 /*，注释里还没有文字），必须根据【注释前的代码】推断并写出");
        sb.append("描述该代码用途的注释，**禁止输出空内容**；\n");
        sb.append("4. 若注释中已有部分文字，则自然续写补全，使注释完整通顺。");
        return sb.toString();
    }

    /** 根据注释生成代码 */
    public static String commentToCodePrompt(String comment, CodeContextCollector.EditorContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("任务：根据注释生成 Java 实现代码。\n");
        if (ctx.filePath != null) sb.append("【文件】").append(ctx.filePath).append('\n');
        if (ctx.packageName != null && !ctx.packageName.isBlank()) sb.append("【包名】").append(ctx.packageName).append('\n');
        if (ctx.importsSummary != null && !ctx.importsSummary.isBlank()) {
            sb.append("【已有导入】\n").append(ctx.importsSummary).append('\n');
        }
        if (ctx.enclosingSignature != null && !ctx.enclosingSignature.isBlank()) {
            sb.append("【所在位置】\n").append(ctx.enclosingSignature).append('\n');
        }
        sb.append("【光标前代码】\n").append(ctx.beforeCaret).append('\n');
        sb.append("【光标后代码】\n").append(ctx.afterCaret).append('\n');
        sb.append("\n【注释】\n").append(comment).append('\n');
        sb.append("\n请根据注释意图生成完整的 Java 实现代码：与已有代码风格一致；"
                + "只输出纯代码文本，不要 ```java 代码块标记、不要解释、不要重复注释内容；"
                + "代码插入位置为注释之后；若位于方法体内，严禁输出 package/import/class 等文件级结构。");
        return sb.toString();
    }

    /** Bug 扫描 */
    public static String scanBugsPrompt(String code) {
        return "你是代码评审专家。请审查下面这段 Java 代码，找出所有潜在的 Bug（空指针、并发问题、资源泄漏、"
                + "逻辑错误、边界问题、异常处理不当等）。\n"
                + "严格只输出一个 JSON 数组，不要任何其他文字，格式如下：\n"
                + "[{\"line\": 行号, \"severity\": \"error\" 或 \"warning\", \"description\": \"问题描述\", "
                + "\"suggestion\": \"修复建议(可含代码)\"}]，line 必须是代码中的实际行号（从 1 开始）。"
                + "没有发现问题时输出 []。\n```java\n" + code + "\n```";
    }

    /** 从回复中提取 ```java 代码块内容；没有代码块则原样返回。 */
    public static String extractCodeBlock(String reply) {
        if (reply == null) return "";
        String trimmed = reply.trim();
        int fenceStart = trimmed.indexOf("```");
        if (fenceStart < 0) {
            // 没有代码块：去掉常见的前缀解释
            int idx = trimmed.indexOf('{');
            return idx >= 0 ? trimmed.substring(idx) : trimmed;
        }
        int langEnd = trimmed.indexOf('\n', fenceStart);
        int contentStart = langEnd < 0 ? fenceStart + 3 : langEnd + 1;
        int fenceEnd = trimmed.indexOf("```", contentStart);
        if (fenceEnd < 0) fenceEnd = trimmed.length();
        return trimmed.substring(contentStart, fenceEnd);
    }
}
