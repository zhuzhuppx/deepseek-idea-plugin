# DeepSeek Expert Assistant — IntelliJ IDEA 插件

基于 DeepSeek API 的 AI 开发助手插件（当前 v1 为 Java 专家模式，后续可扩展其他语言专家模式）。

## 功能

- **行内代码补全**：输入时智能建议灰色补全文本，`Tab` 接受、`Esc` 取消
- **代码解释 / 优化重构 / 生成单元测试**：选中代码 → 右键 → DeepSeek 菜单
- **根据注释生成代码**：光标放在注释上 → 右键 → DeepSeek: 根据注释生成代码，「应用」插入到注释后
- **Bug 扫描**：右键 → 扫描当前文件，问题标注到对应行（红=错误，黄=警告），可定位/复制建议
- **全项目上下文**：自动注入项目结构、依赖摘要、import 相关源码文件
- **记忆系统**：跨会话记住项目事实、偏好与近期对话（设置面板可管理）

## 环境要求

- IntelliJ IDEA 2026.1.x（Platform 261，本机为 IU-261.25134.95）
- JDK 17（构建用，`gradle.properties` 已指定 `org.gradle.java.home` 为 corretto-17.0.19；
  注意不要改成 Microsoft JDK，其 `instrumentCode` 会因缺少 `Packages` 目录而失败）
- DeepSeek API Key（[platform.deepseek.com](https://platform.deepseek.com)）

## 构建

```bat
set "JAVA_HOME=%USERPROFILE%\.jdks\corretto-17.0.19"
D:\work\gradle-8.14\bin\gradle.bat build
```

产物：`build/distributions/deepseek-idea-plugin-0.1.0.zip`

## 安装

1. IDEA → Settings → Plugins → ⚙️ → Install Plugin from Disk…
2. 选择 `build/distributions/deepseek-idea-plugin-0.1.0.zip`，重启 IDE
3. Settings → Tools → DeepSeek Java Expert 填入 API Key，点「测试连接」

## 使用

- 输入代码停顿片刻 → 灰色建议 → `Tab` 接受
- 选中代码右键 → DeepSeek → 解释 / 优化 / 生成测试
- 光标在注释行内 → 右键 → DeepSeek → 根据注释生成代码
- 右键 → DeepSeek → 扫描当前文件 Bug

## 技术说明

- 插件主体用 Java 17，构建用 Gradle + org.jetbrains.intellij 插件，SDK 直接使用本机 IDEA（localPath）
- 行内补全使用 2025.3 平台的 Kotlin 协程 API（`inline.completion.provider` 扩展点），
  因此 `DeepSeekInlineCompletionProvider` 是一段 ~60 行的 Kotlin 适配器，桥接 Java 业务代码
- HTTP 使用 JDK 内置 HttpClient + SSE 流式解析，JSON 使用平台自带 Gson
