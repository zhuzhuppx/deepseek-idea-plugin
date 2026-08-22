package com.deepseek.plugin.context;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.SourceFolder;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiImportStatement;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.search.GlobalSearchScope;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 项目上下文扫描：项目结构、依赖摘要、与当前文件相关的源码文件。
 * 全部有大小/数量上限，避免提示词爆炸。
 *
 * <p>性能（2026-08-14）：右键菜单每次请求都会 buildProjectContext，
 * 若每次都全量遍历几千个 Java 文件，EDT 会被卡住数秒到数十秒。
 * 因此项目结构结果按项目缓存 10 分钟、相关文件按 文件路径+修改时间 缓存 30 秒；
 * 设置面板「扫描项目」可调用 invalidateStructureCache 强制刷新。
 */
public final class ProjectScanner {

    private static final Logger LOG = Logger.getInstance(ProjectScanner.class);

    private static final long STRUCTURE_TTL_MS = 10 * 60 * 1000L;  // 项目结构缓存 10 分钟
    private static final long RELATED_TTL_MS = 30 * 1000L;         // 相关文件缓存 30 秒
    private static final long LARGE_FILE_BYTES = 64 * 1024L;       // 超过此大小不统计行数

    private static final Map<String, CachedEntry> STRUCTURE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CachedEntry> RELATED_CACHE = new ConcurrentHashMap<>();

    private static final class CachedEntry {
        final Object value;
        final long ts;

        CachedEntry(Object value) {
            this.value = value;
            this.ts = System.currentTimeMillis();
        }
    }

    private ProjectScanner() {
    }

    /** 让某项目的结构缓存失效（设置面板「扫描项目」用，强制重新扫描）。 */
    public static void invalidateStructureCache(Project project) {
        if (project != null) {
            STRUCTURE_CACHE.remove(cacheKey(project));
        }
    }

    private static String cacheKey(Project project) {
        return project.getName() + "@" + project.getBasePath();
    }

    /** 收集项目结构（包树 + 文件清单 + 行数），返回文本摘要。带 TTL 缓存。 */
    public static String collectProjectStructure(Project project, int maxFiles, int maxLines) {
        if (project == null || project.isDisposed()) return "";
        String key = cacheKey(project) + "|" + maxFiles + "|" + maxLines;
        CachedEntry hit = STRUCTURE_CACHE.get(key);
        if (hit != null && System.currentTimeMillis() - hit.ts < STRUCTURE_TTL_MS) {
            return (String) hit.value;
        }

        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        Map<String, List<String>> packages = new TreeMap<>();
        int[] fileCount = {0};

        for (VirtualFile root : project.getBaseDir() == null ? new VirtualFile[0]
                : new VirtualFile[]{project.getBaseDir()}) {
            walk(root, index, packages, fileCount, maxFiles);
        }

        StringBuilder sb = new StringBuilder("项目文件结构（包 → 文件[行数]）:\n");
        for (Map.Entry<String, List<String>> entry : packages.entrySet()) {
            if (sb.length() > maxLines * 40) break;
            sb.append(entry.getKey().isEmpty() ? "(默认包)" : entry.getKey()).append('\n');
            for (String f : entry.getValue()) {
                sb.append("  ").append(f).append('\n');
                if (sb.length() > maxLines * 40) break;
            }
        }
        String text = sb.toString();
        STRUCTURE_CACHE.put(key, new CachedEntry(text));
        return text;
    }

    private static void walk(VirtualFile dir, ProjectFileIndex index,
                             Map<String, List<String>> packages, int[] fileCount, int maxFiles) {
        if (fileCount[0] >= maxFiles) return;
        VirtualFile[] children = dir.getChildren();
        for (VirtualFile child : children) {
            if (fileCount[0] >= maxFiles) return;
            if (child.isDirectory()) {
                if (isSkipDir(child.getName())) continue;
                walk(child, index, packages, fileCount, maxFiles);
            } else if (child.getName().endsWith(".java")) {
                if (!index.isInSourceContent(child)) continue;
                String pkg = relativePackage(child, index);
                int lines = countLines(child);
                String suffix = lines < 0 ? " (大文件)" : " (" + lines + " 行)";
                packages.computeIfAbsent(pkg, k -> new ArrayList<>())
                        .add(child.getName() + suffix);
                fileCount[0]++;
            }
        }
    }

    private static boolean isSkipDir(String name) {
        return name.equals("target") || name.equals("build") || name.equals(".git")
                || name.equals("node_modules") || name.equals(".idea") || name.equals("out");
    }

    private static String relativePackage(VirtualFile file, ProjectFileIndex index) {
        VirtualFile sourceRoot = index.getSourceRootForFile(file);
        if (sourceRoot == null) return "";
        String rel = FileUtil.getRelativePath(sourceRoot.getPath(), file.getPath(), '/');
        if (rel == null) return "";
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? "" : rel.substring(0, slash).replace('/', '.');
    }

    private static int countLines(VirtualFile file) {
        // 大文件不读全文统计行数，避免全项目扫描时读入巨量字节导致极慢/内存压力
        if (file.getLength() > LARGE_FILE_BYTES) return -1;
        try {
            String text = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
            int n = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') n++;
            }
            return n + 1;
        } catch (IOException e) {
            return -1;
        }
    }

    /** 收集构建依赖摘要（pom.xml / build.gradle）。 */
    public static String collectDependencies(Project project, int maxLines) {
        StringBuilder sb = new StringBuilder();
        VirtualFile base = project.getBaseDir();
        if (base != null) {
            VirtualFile pom = base.findChild("pom.xml");
            if (pom != null && pom.exists()) {
                sb.append("依赖(pom.xml):\n").append(extractPomDeps(pom, maxLines));
                return sb.toString();
            }
            VirtualFile gradle = base.findChild("build.gradle");
            if (gradle != null && gradle.exists()) {
                sb.append("依赖(build.gradle):\n").append(extractGradleDeps(gradle, maxLines));
                return sb.toString();
            }
        }
        return "未找到 pom.xml / build.gradle";
    }

    private static String extractPomDeps(VirtualFile pom, int maxLines) {
        try {
            String text = new String(pom.contentsToByteArray(), StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            String[] lines = text.split("\\R");
            for (int i = 0; i < lines.length && sb.length() / 40 < maxLines; i++) {
                String line = lines[i].trim();
                if (line.startsWith("<groupId>") || line.startsWith("<artifactId>")
                        || line.startsWith("<version>") || line.startsWith("<scope>")) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString().isEmpty() ? "(解析为空)" : sb.toString();
        } catch (IOException e) {
            return "(读取失败)";
        }
    }

    private static String extractGradleDeps(VirtualFile gradle, int maxLines) {
        try {
            String text = new String(gradle.contentsToByteArray(), StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            String[] lines = text.split("\\R");
            for (String raw : lines) {
                String line = raw.trim();
                if ((line.startsWith("implementation") || line.startsWith("compile")
                        || line.startsWith("api") || line.startsWith("testImplementation")
                        || line.startsWith("testCompile")) && line.contains(":")) {
                    sb.append(line).append('\n');
                    if (sb.length() / 40 >= maxLines) break;
                }
            }
            return sb.toString().isEmpty() ? "(解析为空)" : sb.toString();
        } catch (IOException e) {
            return "(读取失败)";
        }
    }

    /** 收集与当前文件相关的源码文件（import 引用的类），返回 path → 内容。带 30s 缓存（按 文件+修改时间）。 */
    public static Map<String, String> collectRelatedFiles(Project project, PsiFile current,
                                                          int maxFiles, int maxCharsPerFile) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!(current instanceof PsiJavaFile)) return result;
        VirtualFile curVf = current.getVirtualFile();
        if (curVf == null) return result;

        String key = project.getBasePath() + "|" + curVf.getPath() + "|" + curVf.getModificationStamp()
                + "|" + maxFiles + "|" + maxCharsPerFile;
        CachedEntry hit = RELATED_CACHE.get(key);
        if (hit != null && System.currentTimeMillis() - hit.ts < RELATED_TTL_MS) {
            @SuppressWarnings("unchecked")
            Map<String, String> cached = (Map<String, String>) hit.value;
            return new LinkedHashMap<>(cached);
        }

        PsiJavaFile javaFile = (PsiJavaFile) current;
        Set<String> fqns = new LinkedHashSet<>();
        PsiImportList importList = javaFile.getImportList();
        if (importList != null) {
            for (PsiImportStatement stmt : importList.getImportStatements()) {
                String qname = stmt.getQualifiedName();
                if (qname != null && !qname.startsWith("java.") && !qname.startsWith("javax.")
                        && !qname.startsWith("jdk.") && !qname.startsWith("sun.")) {
                    fqns.add(qname);
                }
            }
        }
        // 当前文件自己的类
        for (PsiClass c : javaFile.getClasses()) {
            fqns.add(c.getQualifiedName());
        }

        int count = 0;
        for (String fqn : fqns) {
            if (count >= maxFiles) break;
            try {
                PsiClass psiClass = JavaPsiFacade.getInstance(project)
                        .findClass(fqn, GlobalSearchScope.allScope(project));
                if (psiClass == null) continue;
                PsiFile file = psiClass.getContainingFile();
                if (file == null || file == current || file.getVirtualFile() == null) continue;
                VirtualFile vf = file.getVirtualFile();
                String path = vf.getPath();
                if (result.containsKey(path)) continue;
                if (!file.isValid()) continue;
                result.put(path, readCapped(vf, maxCharsPerFile));
                count++;
            } catch (Exception e) {
                LOG.debug("解析相关类失败: " + fqn, e);
            }
        }
        if (!result.isEmpty()) {
            RELATED_CACHE.put(key, new CachedEntry(new LinkedHashMap<>(result)));
        }
        return result;
    }

    private static String readCapped(VirtualFile file, int maxChars) {
        try {
            String text = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
            return text.length() <= maxChars ? text : text.substring(0, maxChars) + "\n// ...(文件过长已截断)";
        } catch (IOException e) {
            return "";
        }
    }

    /** 生成给 AI 看的项目上下文段落。 */
    public static String buildProjectContext(Project project, PsiFile currentFile, int maxFiles, int maxCharsPerFile) {
        StringBuilder sb = new StringBuilder();
        sb.append("【项目环境】\n");
        sb.append("项目名称: ").append(project.getName()).append('\n');
        sb.append(collectDependencies(project, 80)).append('\n');
        sb.append(collectProjectStructure(project, maxFiles, 120)).append('\n');

        Map<String, String> related = collectRelatedFiles(project, currentFile, 4, maxCharsPerFile);
        if (!related.isEmpty()) {
            sb.append("\n【相关源码文件】\n");
            for (Map.Entry<String, String> entry : related.entrySet()) {
                sb.append("===== ").append(entry.getKey()).append(" =====\n")
                        .append(entry.getValue()).append('\n');
            }
        }
        return sb.toString();
    }
}
