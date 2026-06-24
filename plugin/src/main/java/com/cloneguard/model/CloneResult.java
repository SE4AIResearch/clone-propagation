package com.cloneguard.model;

public class CloneResult {

    public final boolean   isClone;
    public final CloneType cloneType;
    public final double    similarity;
    public final String    matchedFunction;
    public final String    layer;
    public final String    suggestedCode;

    // AI Detection fields
    public final boolean   isAiGenerated;
    public final double    aiConfidence;      // 0.0 – 1.0
    public final String    aiLabel;           // "AI Generated — High Confidence" etc.

    private CloneResult(Builder b) {
        this.isClone         = b.isClone;
        this.cloneType       = b.cloneType;
        this.similarity      = b.similarity;
        this.matchedFunction = b.matchedFunction;
        this.layer           = b.layer;
        this.suggestedCode   = b.suggestedCode;
        this.isAiGenerated   = b.isAiGenerated;
        this.aiConfidence    = b.aiConfidence;
        this.aiLabel         = b.aiLabel;
    }

    public static CloneResult noClone() {
        return new Builder().isClone(false).similarity(0).layer("—").build();
    }

    public static class Builder {
        boolean    isClone;
        CloneType  cloneType;
        double     similarity;
        String     matchedFunction = "";
        String     layer           = "";
        String     suggestedCode   = "";
        boolean    isAiGenerated   = false;
        double     aiConfidence    = 0.0;
        String     aiLabel         = "Unknown";

        public Builder isClone(boolean v)           { isClone = v;         return this; }
        public Builder cloneType(CloneType v)        { cloneType = v;       return this; }
        public Builder similarity(double v)          { similarity = v;      return this; }
        public Builder matchedFunction(String v)     { matchedFunction = v; return this; }
        public Builder layer(String v)               { layer = v;           return this; }
        public Builder suggestedCode(String v)       { suggestedCode = v;   return this; }
        public Builder isAiGenerated(boolean v)      { isAiGenerated = v;   return this; }
        public Builder aiConfidence(double v)        { aiConfidence = v;    return this; }
        public Builder aiLabel(String v)             { aiLabel = v;         return this; }
        public CloneResult build()                   { return new CloneResult(this); }
    }

    @Override
    public String toString() {
        if (!isClone) return "No clone detected";
        return String.format("%s (%.0f%%) matched '%s' via %s | AI: %s (%.0f%%)",
                cloneType.label, similarity * 100, matchedFunction, layer,
                isAiGenerated ? "Yes" : "No", aiConfidence * 100);
    }
}
