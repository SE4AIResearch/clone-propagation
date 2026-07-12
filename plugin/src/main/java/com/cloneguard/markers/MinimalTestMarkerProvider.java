package com.cloneguard.markers;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * TEMPORARY DIAGNOSTIC — marks EVERY method in EVERY Java file with an
 * icon, unconditionally. No marker lookup, no CloneGuard logic at all.
 * Purpose: isolate whether the IntelliJ platform is invoking ANY custom
 * LineMarkerProvider at all in this environment, before debugging
 * CloneLineMarkerProvider's own marker-lookup logic further.
 *
 * If this ALSO shows nothing, the problem is registration/platform-level.
 * If this DOES show an icon on every method, the problem is specifically
 * inside CloneLineMarkerProvider's marker lookup, not registration.
 */
public class MinimalTestMarkerProvider implements LineMarkerProvider {

    private static final Logger LOG = Logger.getInstance(MinimalTestMarkerProvider.class);

    @Nullable
    @Override
    public LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        if (!(element instanceof PsiIdentifier)) return null;
        if (!(element.getParent() instanceof PsiMethod method)) return null;
        if (method.getNameIdentifier() != element) return null;

        LOG.info("MinimalTestMarkerProvider DEBUG: marking method '" + method.getName() + "' unconditionally");

        return new LineMarkerInfo<>(
                element,
                element.getTextRange(),
                AllIcons.General.Warning,
                psiElement -> "TEST MARKER — always shown",
                null,
                GutterIconRenderer.Alignment.LEFT,
                () -> "Test marker"
        );
    }
}