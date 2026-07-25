package com.cloneguard.settings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * FIX (professor-flagged, 4.2): PythonServerClient previously had its
 * target server URL hardcoded as a compile-time constant
 * ("http://localhost:8765"), meaning a developer wanting to point the
 * plugin at a shared team server or a cloud-hosted instance would have
 * had to modify source and rebuild the plugin themselves. This is an
 * IDE-wide (application-level, not per-project) persistent setting --
 * unlike CloneIndexService's index data, which genuinely needs to be
 * scoped per-project, a "which server do I talk to" preference is a
 * user-level choice that should stay consistent across every project a
 * developer opens, so Service.Level.APP is the correct scope here.
 *
 * Persisted to CloneGuardSettings.xml in the IDE's config directory,
 * following the standard PersistentStateComponent pattern.
 */
@State(
        name = "com.cloneguard.settings.CloneGuardSettings",
        storages = @Storage("CloneGuardSettings.xml")
)
@Service(Service.Level.APP)
public final class CloneGuardSettings implements PersistentStateComponent<CloneGuardSettings.State> {

    // UPDATED: was "http://localhost:8765", requiring every user to run
    // the Python server locally themselves before the plugin could do
    // any Layer 2 (CodeBERT/semantic) detection at all. Now points at a
    // persistently hosted instance on Render (Standard tier, 2GB RAM —
    // needed for CodeBERT + UniXcoder to load reliably), confirmed live
    // and reachable via a real GET /health check returning
    // {"status":"ok","models":["codebert","unixcoder"]} before this was
    // changed. Anyone installing the plugin zip now gets working Layer 2
    // detection immediately, with zero local setup required — the
    // original goal behind this whole change.
    public static final String DEFAULT_SERVER_URL = "https://cloneguard-server.onrender.com";

    /** Plain data holder — PersistentStateComponent serializes public fields directly. */
    public static class State {
        public String serverUrl = DEFAULT_SERVER_URL;
    }

    private State myState = new State();

    public static CloneGuardSettings getInstance() {
        return ApplicationManager.getApplication().getService(CloneGuardSettings.class);
    }

    @Override
    public @Nullable State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
    }

    /**
     * Returns the configured server URL, falling back to the default if
     * unset/blank, with any trailing slash stripped so callers can safely
     * concatenate a path directly (e.g. getServerUrl() + "/health").
     */
    public String getServerUrl() {
        String url = myState.serverUrl;
        if (url == null || url.isBlank()) {
            return DEFAULT_SERVER_URL;
        }
        url = url.trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public void setServerUrl(String url) {
        myState.serverUrl = url;
    }
}