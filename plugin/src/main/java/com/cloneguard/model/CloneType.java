package com.cloneguard.model;

public enum CloneType {
    TYPE_1("Type 1 — Exact Clone",       "Identical code, character-for-character match",          "Layer 1 (Local)"),
    TYPE_2("Type 2 — Renamed Clone",     "Same structure, different variable/method names",         "Layer 1 (Local)"),
    TYPE_3("Type 3 — Near-Miss Clone",   "Similar structure with minor additions or modifications", "Layer 2 (Server)"),
    TYPE_4("Type 4 — Semantic Clone",    "Same intent, completely different implementation",         "Layer 2 (Server)");

    public final String label;
    public final String description;
    public final String layer;

    CloneType(String label, String description, String layer) {
        this.label       = label;
        this.description = description;
        this.layer       = layer;
    }
}
