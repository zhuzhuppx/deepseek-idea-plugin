package com.deepseek.plugin.context;

import com.deepseek.plugin.settings.DeepSeekState;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 项目打开后的后台自动扫描：预热 ProjectScanner 的项目结构缓存，
 * 避免第一次右键/补全/设置面板查看时现场全量遍历几千个文件卡住 UI。
 *
 * <p>流程：项目打开（postStartupActivity）→ 等待索引就绪（smart 模式）→ 后台线程
 * ReadAction 内扫描项目结构与依赖摘要 → 结果进入 ProjectScanner 的 10 分钟缓存。
 * 全程不阻塞 EDT，失败只记日志不打扰用户。
 */
public final class ProjectStartupScanner implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(ProjectStartupScanner.class);

    @Override
    public @Nullable Object execute(@NotNull Project project,
                                    @NotNull Continuation<? super Unit> continuation) {
        if (project.isDisposed()) return Unit.INSTANCE;
        DumbService.getInstance(project).runWhenSmart(() -> {
            if (project.isDisposed()) return;
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                long start = System.currentTimeMillis();
                try {
                    DeepSeekState state = DeepSeekState.getInstance();
                    ReadAction.run(() -> {
                        // 与设置面板「扫描项目」一致：展示更多文件，预热完整结构缓存
                        ProjectScanner.buildProjectContext(project, null, 800,
                                state.contextMaxFileChars);
                    });
                    LOG.info("后台项目扫描完成: " + project.getName()
                            + "，耗时 " + (System.currentTimeMillis() - start) + "ms");
                } catch (Throwable t) {
                    LOG.warn("后台项目扫描失败: " + project.getName(), t);
                }
            });
        });
        return Unit.INSTANCE;
    }
}
