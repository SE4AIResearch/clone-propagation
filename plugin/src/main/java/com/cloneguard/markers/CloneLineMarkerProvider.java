package com.cloneguard.markers;

import com.cloneguard.refactor.ExtractMethodEngine;
import com.cloneguard.services.PendingCloneMarkerService;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopupStep;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;
import com.intellij.ui.awt.RelativePoint;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.MouseEvent;
import java.util.List;

/**
 * SCENARIO 1 — the gutter icon.
 *
 * Draws an icon next to the line number of any method currently registered
 * in PendingCloneMarkerService — i.e. a method that was pasted, detected as
 * a clone, and the user chose Accept Anyway or Dismiss (leaving the
 * duplicate in place rather than deleting it). Clicking the icon shows the
 * clone info and offers to refactor it using the same ExtractMethodEngine
 * Scenario 2's button already uses.
 *
 * IntelliJ calls getLineMarkerInfo() for every leaf PSI element during
 * highlighting, so this filters down aggressively to just "is this element
 * the NAME identifier of a method" before doing any real work.
 */
public class CloneLineMarkerProvider implements LineMarkerProvider {

    private static final Logger LOG = Logger.getInstance(CloneLineMarkerProvider.class);

    @Nullable
    @Override
    public LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        if (!(element instanceof PsiIdentifier)) return null;
        if (!(element.getParent() instanceof PsiMethod method)) return null;
        if (method.getNameIdentifier() != element) return null;

        // DEBUG: this line alone confirms the provider is even being
        // called for method-name elements at all — if this never appears
        // in the log, IntelliJ isn't invoking our provider, which would
        // point back to the plugin.xml registration rather than anything
        // in this class's own logic.
        LOG.info("CloneLineMarkerProvider DEBUG: checking method '" + method.getName() + "'");

        Project project = element.getProject();
        if (element.getContainingFile() == null) return null;
        VirtualFile vf = element.getContainingFile().getVirtualFile();

        if (vf == null) return null;

        PendingCloneMarkerService.Marker marker =
                PendingCloneMarkerService.getInstance(project).getMarker(vf, method.getName());

        // DEBUG: confirms whether this specific method has a registered
        // marker at the moment IntelliJ asks — if getLineMarkerInfo() logs
        // above but this always logs "marker=null", the marker was never
        // successfully added (or was added under a different file/name).
        LOG.info("CloneLineMarkerProvider DEBUG: method='" + method.getName() + "' file=" + vf.getName()
                + " marker=" + (marker != null ? "FOUND (matched=" + marker.canonicalMethodName + ")" : "null"));

        if (marker == null) return null;

        return new LineMarkerInfo<>(
                element,
                element.getTextRange(),
                AllIcons.General.Warning,
                psiElement -> "CloneGuard: possible duplicate of " + marker.canonicalMethodName
                        + "() (" + marker.cloneTypeLabel + ", " + Math.round(marker.similarity * 100) + "%) — click to refactor",
                (event, psiElement) -> showRefactorPopup(project, vf, method.getName(), marker, event),
                GutterIconRenderer.Alignment.LEFT,
                () -> "CloneGuard clone marker"
        );
    }

    private void showRefactorPopup(Project project, VirtualFile vf, String duplicateMethodName,
                                    PendingCloneMarkerService.Marker marker, MouseEvent event) {
        List<String> options = List.of(
                "Refactor with Extract Method",
                "Dismiss"
        );

        ListPopupStep<String> step = new BaseListPopupStep<>(
                "CloneGuard — " + marker.cloneTypeLabel + " of " + marker.canonicalMethodName + "()",
                options
        ) {
            @Override
            public PopupStep<?> onChosen(String selectedValue, boolean finalChoice) {
                if ("Refactor with Extract Method".equals(selectedValue)) {
                    ExtractMethodEngine.getInstance(project).extract(
                            marker.canonicalMethodName,
                            duplicateMethodName,
                            marker.cloneTypeLabel,
                            (psiFile) -> PendingCloneMarkerService.getInstance(project)
                                    .removeMarker(vf, duplicateMethodName)
                    );
                }
                return FINAL_CHOICE;
            }
        };

        JBPopupFactory.getInstance().createListPopup(step).show(new RelativePoint(event));
    }
}