package com.deepseek.plugin.completion;

/**
 * 代码补全场景枚举。
 * 每个场景对应一类补全需求，有独立的 prompt 构建与触发条件。
 */
public enum CompletionScene {

    /** 注释转代码实现：解析自然语言注释生成代码 */
    COMMENT_TO_CODE("comment-to-code", "注释转代码"),

    /** 代码续写：在方法/语句结尾续写下一个合理片段 */
    CODE_CONTINUATION("code-continuation", "代码续写"),

    /** 链式调用预测：Builder/Stream 中预测下一个方法 */
    CHAIN_CALL_PREDICTION("chain-call", "链式调用预测"),

    /** 样板代码生成：Entity/DTO/VO 字段、Getter/Setter/Builder */
    BOILERPLATE("boilerplate", "样板代码生成"),

    /** 智能异常处理：try-catch 块补全 */
    EXCEPTION_HANDLING("exception", "异常处理"),

    /** 单元测试脚手架：Given-When-Then + Mock */
    TEST_SKELETON("test", "测试脚手架"),

    /** 框架 API 惯用法：Spring/MyBatis-Plus 调用链 */
    FRAMEWORK_API("framework", "框架 API 惯用法"),

    /** 接口/抽象方法实现：生成未实现方法体 */
    IMPLEMENT_METHOD("implement", "方法实现"),

    /** SQL/Repository 辅助：Mapper 注解、Query 方法 */
    SQL_REPOSITORY("sql", "SQL/Repository 辅助"),

    /** 导入语句补全 */
    IMPORT_SUGGESTION("import", "导入语句补全"),

    /** JSON/XML 结构补全 */
    JSON_XML("json-xml", "JSON/XML 补全"),

    /** 配置键值绑定：@Value、application.yml */
    CONFIG_KEY("config", "配置键补全"),

    /** 正则表达式构建 */
    REGEX_BUILD("regex", "正则表达式"),

    /** 国际化键补全：i18n */
    I18N_KEY("i18n", "国际化键"),

    /** 注解补全：正在输入 @xxx，补全注解名 */
    ANNOTATION("annotation", "注解补全"),

    /** 成员声明区补全：类成员区（字段/注解位置），补全注解+字段声明 */
    MEMBER_DECLARATION("member", "成员声明补全"),

    /** 普通上下文无明确场景：不触发 */
    NONE("none", "无场景");

    final String id;
    final String displayName;

    CompletionScene(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }
}
