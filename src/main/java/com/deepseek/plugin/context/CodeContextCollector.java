package com.deepseek.plugin.context;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiClass;
import com.intellij.psi.util.PsiTreeUtil;

/**
 * 收集当前编辑位置的代码上下文（光标前后文本、所在方法签名、导入摘要等）。
 */
public final class CodeContextCollector {

    private CodeContextCollector() {
    }

    public static final class EditorContext {
        public String filePath;
        public String packageName;
        public String importsSummary;
        public String enclosingSignature;
        public String beforeCaret;
        public String afterCaret;
        public String commentText;   // 光标所在注释内容（注释生成代码用）

        // ===== PSI 感知信息（供场景识别器做语法树级判断）=====
        /** 光标所在 PSI 元素类型（如 PsiComment/PsiStringLiteral/PsiMethod 等，简化名） */
        public String caretElementType;
        /** 光标是否在注释内（PSI 判定） */
        public boolean inComment;
        /** 光标是否在字符串字面量内 */
        public boolean inStringLiteral;
        /** 光标是否在 import 语句区域 */
        public boolean inImportArea;
        /** 光标所在行文本 */
        public String caretLine;
        /** 类体是否为空/仅注解（样板代码场景） */
        public boolean classBodyEmpty;
        /** 当前类名（样板代码判断 DTO/VO/Entity 后缀） */
        public String className;
        /** 类名是否匹配 DTO/VO/Entity/BO 等样板后缀 */
        public boolean boilerplateType;
        /** 当前是否有未实现方法（接口实现场景） */
        public boolean hasUnimplementedMethods;
        /** 当前方法是否有 @Test 注解（测试场景） */
        public boolean inTestMethod;
        /** 行注释内容（语义意图场景：上一行注释） */
        public String prevLineComment;
    }

    /** 光标前/后最大字符数 */
    private static final int BEFORE_MAX = 6000;
    private static final int AFTER_MAX = 1200;

    public static EditorContext collect(Editor editor, PsiFile psiFile) {
        EditorContext ctx = new EditorContext();
        Document document = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();

        if (psiFile != null && psiFile.getVirtualFile() != null) {
            ctx.filePath = psiFile.getVirtualFile().getPath();
        }
        if (psiFile instanceof PsiJavaFile javaFile) {
            ctx.packageName = javaFile.getPackageName();
            StringBuilder imports = new StringBuilder();
            if (javaFile.getImportList() != null) {
                for (var stmt : javaFile.getImportList().getImportStatements()) {
                    String qname = stmt.getQualifiedName();
                    if (qname == null) continue;
                    imports.append("import ").append(qname).append(";\n");
                    if (imports.length() > 2000) {
                        imports.append("// ...(导入过多已截断)");
                        break;
                    }
                }
            }
            ctx.importsSummary = imports.toString();
        }

        PsiElement atCaret = psiFile != null ? psiFile.findElementAt(offset) : null;
        if (atCaret != null) {
            // PSI 感知信息
            ctx.caretElementType = atCaret.getClass().getSimpleName().replace("Psi", "").replace("Impl", "");
            ctx.inComment = atCaret.getNode() != null
                    && atCaret.getNode().getElementType().toString().contains("COMMENT");
            ctx.inStringLiteral = atCaret instanceof com.intellij.psi.PsiLiteralExpression
                    || (atCaret.getParent() instanceof com.intellij.psi.PsiLiteralExpression)
                    || "STRING_LITERAL".equals(ctx.caretElementType);
            // 行文本
            try {
                int line = document.getLineNumber(offset);
                int ls = document.getLineStartOffset(line);
                int le = document.getLineEndOffset(line);
                ctx.caretLine = document.getText(new com.intellij.openapi.util.TextRange(ls, le));
            } catch (Exception ignored) {
            }

            PsiMethod method = PsiTreeUtil.getParentOfType(atCaret, PsiMethod.class);
            PsiClass clazz = PsiTreeUtil.getParentOfType(atCaret, PsiClass.class);
            if (clazz != null) {
                ctx.className = clazz.getName();
                // 样板类型：DTO/VO/Entity/BO/DO/Query/Req/Resp
                String cn = clazz.getName();
                if (cn != null) {
                    String upper = cn.toUpperCase(java.util.Locale.ROOT);
                    ctx.boilerplateType = upper.endsWith("DTO") || upper.endsWith("VO")
                            || upper.endsWith("ENTITY") || upper.endsWith("BO")
                            || upper.endsWith("DO") || upper.endsWith("QUERY")
                            || upper.endsWith("REQ") || upper.endsWith("RESP");
                }
                // 类体是否为空/仅注解
                try {
                    ctx.classBodyEmpty = clazz.getFields().length == 0
                            && clazz.getMethods().length == 0;
                } catch (Throwable ignored) {
                    ctx.classBodyEmpty = false;
                }
            }
            if (method != null) {
                // 是否有 @Test 注解
                ctx.inTestMethod = method.getAnnotation("Test") != null
                        || method.getAnnotation("org.junit.jupiter.api.Test") != null;
            }
            // 接口/抽象类未实现方法
            if (clazz != null && (clazz.isInterface() || clazz.hasModifierProperty("abstract"))) {
                ctx.hasUnimplementedMethods = clazz.getMethods().length > 0;
            }
            StringBuilder sig = new StringBuilder();
            if (clazz != null) sig.append("类: ").append(clazz.getQualifiedName() == null
                    ? clazz.getName() : clazz.getQualifiedName());
            if (method != null) {
                sig.append("\n方法: ").append(method.getName())
                        .append(method.getParameterList().getText());
                PsiClass containing = method.getContainingClass();
                if (containing != null) {
                    sig.append(" (位于 ").append(containing.getQualifiedName()).append(')');
                }
            }
            ctx.enclosingSignature = sig.toString();
            ctx.commentText = extractCommentText(psiFile, offset);
        }

        // import 区域判断：
        // 1) import 语句内部（含 import 之间的空行/注释）
        // 2) import 区域结束之后、第一个类声明之前（顶层空白区）—— 否则该处会被误判为
        //    样板代码/方法实现等场景，生成方法/字段插入 import 区域导致语法错误
        if (psiFile instanceof PsiJavaFile javaFile && javaFile.getImportList() != null) {
            var importList = javaFile.getImportList();
            int importEnd = importList.getTextRange() != null ? importList.getTextRange().getEndOffset() : -1;
            int classStart = -1;
            PsiClass[] classes = javaFile.getClasses();
            if (classes.length > 0 && classes[0].getTextRange() != null) {
                classStart = classes[0].getTextRange().getStartOffset();
            }
            int packageEnd = -1;
            if (javaFile.getPackageStatement() != null && javaFile.getPackageStatement().getTextRange() != null) {
                packageEnd = javaFile.getPackageStatement().getTextRange().getEndOffset();
            }
            boolean inList = atCaret != null
                    && PsiTreeUtil.isAncestor(importList, atCaret, false);
            ctx.inImportArea = (inList || (importEnd >= 0 && offset <= importEnd))
                    || (classStart >= 0 && offset > Math.max(0, packageEnd) && offset < classStart);
        }

        int docLen = document.getTextLength();
        int beforeStart = Math.max(0, offset - BEFORE_MAX);
        int afterEnd = Math.min(docLen, offset + AFTER_MAX);
        ctx.beforeCaret = document.getText(new com.intellij.openapi.util.TextRange(beforeStart, offset));
        ctx.afterCaret = document.getText(new com.intellij.openapi.util.TextRange(offset, afterEnd));

        // 上一行注释（语义意图：注释转代码）
        ctx.prevLineComment = extractPrevLineComment(ctx.beforeCaret);
        return ctx;
    }

    /** 提取光标前最近的单行注释（上一行或当前行注释内容）。 */
    private static String extractPrevLineComment(String before) {
        if (before == null || before.isBlank()) return "";
        // 取最后两行
        String[] lines = before.split("\n");
        if (lines.length == 0) return "";
        StringBuilder comment = new StringBuilder();
        for (int i = Math.max(0, lines.length - 2); i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("//")) {
                if (comment.length() > 0) comment.append(' ');
                comment.append(line.substring(2).trim());
            } else if (line.startsWith("/*") || line.startsWith("*")) {
                if (comment.length() > 0) comment.append(' ');
                comment.append(line.replace("/*", "").replace("*/", "").replace("*", "").trim());
            } else if (!line.isEmpty() && comment.length() > 0) {
                // 遇到非注释代码行，若已收集注释则停止
                break;
            }
        }
        return comment.toString().trim();
    }

    /** 提取光标所在行及之前的连续注释行（含块注释中的当前行）。 */
    private static String extractCommentText(PsiFile file, int offset) {
        PsiElement el = file.findElementAt(offset);
        if (el == null) return "";
        String text = el.getText();
        if (text == null || text.isBlank()) return "";
        if (!(el.getNode() != null && el.getNode().getElementType().toString().contains("COMMENT"))) {
            return "";
        }
        return text;
    }
}
