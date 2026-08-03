package com.cloneguard.startup;

import com.cloneguard.services.MetricsTrackerService;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * NEW (Scenario 3 dashboard support): runs once when a project is
 * opened, and pulls in any GitHub PR-sourced refactor sessions that
 * apply-refactors.yml has committed to .cloneguard/pr-refactor-log.json
 * since this project was last opened locally -- see
 * MetricsTrackerService.importGithubPrSessionsIfPresent() for the actual
 * read-and-merge logic this just triggers.
 *
 * Deliberately a project-open-time import rather than something the
 * Trend Dashboard triggers on every refresh: the log file only changes
 * when a genuinely new PR /refactor comment gets resolved on GitHub,
 * which for any single developer's local IDE session is an occasional
 * event, not something worth re-checking (and paying a file-read cost
 * for) on every dashboard interaction.
 *
 * ProjectActivity is the modern replacement for the older
 * StartupActivity interface (deprecated on current IntelliJ Platform
 * versions) -- implementing this one directly rather than the
 * deprecated interface, consistent with targeting a 2026-era platform
 * baseline elsewhere in this plugin (see build.gradle's platform
 * version).
 */
public class CloneGuardStartupActivity implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(CloneGuardStartupActivity.class);

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        try {
            MetricsTrackerService.getInstance(project).importGithubPrSessionsIfPresent();
        } catch (Throwable t) {
            // Never let a startup-time failure here block the IDE or the
            // rest of plugin startup -- this is a best-effort convenience
            // import, not something any other functionality depends on.
            LOG.warn("CloneGuard: failed to import GitHub PR session log on startup: " + t.getMessage());
        }
        return Unit.INSTANCE;
    }
}