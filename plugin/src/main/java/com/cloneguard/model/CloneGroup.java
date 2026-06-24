package com.cloneguard.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A group of methods in the same file that are clones of each other.
 * Used by the FileScannerService for Scenario 2 (A Posteriori Scan).
 */
public class CloneGroup {

    public final CloneType   cloneType;
    public final double      similarity;
    public final List<String> methods;   // method signatures / names
    public final String      detail;

    public CloneGroup(CloneType cloneType, double similarity, List<String> methods, String detail) {
        this.cloneType  = cloneType;
        this.similarity = similarity;
        this.methods    = new ArrayList<>(methods);
        this.detail     = detail;
    }

    public String getSummary() {
        return String.format("[%s] %.0f%% — %s",
                cloneType.label, similarity * 100, String.join(" ↔ ", methods));
    }
}
