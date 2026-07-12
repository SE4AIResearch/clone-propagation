package com.cloneguard.services;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which methods currently have an unresolved "this was pasted as a
 * clone" marker — i.e. the gutter icon Scenario 1 shows after a paste is
 * detected as a clone and the user picks Accept Anyway or Dismiss (both
 * leave the duplicate code sitting in the file). Use Existing Function
 * deletes the duplicate entirely, so no marker is ever added for that case.
 *
 * One instance per project, in-memory only — markers don't need to survive
 * an IDE restart; they're a "you have an unresolved paste in this session"
 * signal, not a persistent record.
 */
public class PendingCloneMarkerService {

    public static class Marker {
        public final String canonicalMethodName;
        public final String cloneTypeLabel;
        public final double similarity;

        public Marker(String canonicalMethodName, String cloneTypeLabel, double similarity) {
            this.canonicalMethodName = canonicalMethodName;
            this.cloneTypeLabel = cloneTypeLabel;
            this.similarity = similarity;
        }
    }

    private static final Map<Project, PendingCloneMarkerService> INSTANCES = new ConcurrentHashMap<>();

    public static PendingCloneMarkerService getInstance(Project project) {
        return INSTANCES.computeIfAbsent(project, p -> new PendingCloneMarkerService());
    }

    // VirtualFile -> (duplicate method name -> Marker)
    private final Map<VirtualFile, Map<String, Marker>> markers = new ConcurrentHashMap<>();

    public void addMarker(VirtualFile file, String duplicateMethodName, Marker marker) {
        markers.computeIfAbsent(file, f -> new ConcurrentHashMap<>()).put(duplicateMethodName, marker);
    }

    public void removeMarker(VirtualFile file, String duplicateMethodName) {
        Map<String, Marker> fileMarkers = markers.get(file);
        if (fileMarkers != null) fileMarkers.remove(duplicateMethodName);
    }

    public Marker getMarker(VirtualFile file, String duplicateMethodName) {
        Map<String, Marker> fileMarkers = markers.get(file);
        return fileMarkers != null ? fileMarkers.get(duplicateMethodName) : null;
    }

    public boolean hasMarker(VirtualFile file, String duplicateMethodName) {
        return getMarker(file, duplicateMethodName) != null;
    }
}