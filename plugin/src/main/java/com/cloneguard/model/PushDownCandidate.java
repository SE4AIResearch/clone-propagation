package com.cloneguard.model;

/**
 * A method declared on a superclass that CloneGuard's static analysis
 * found is only ever referenced from ONE of that superclass's direct
 * subclasses — a candidate for Push Down Method.
 *
 * Distinct from CloneGroup: this isn't about two duplicated methods, it's
 * about one method that's misplaced too high in the class hierarchy. See
 * FileScannerService.findPushDownCandidates() for how this is detected.
 */
public class PushDownCandidate {

    public final String methodName;
    public final String superClassName;
    public final String targetSubclassName;

    public PushDownCandidate(String methodName, String superClassName, String targetSubclassName) {
        this.methodName = methodName;
        this.superClassName = superClassName;
        this.targetSubclassName = targetSubclassName;
    }

    public String getSummary() {
        return methodName + "() — declared on " + superClassName + ", only used by " + targetSubclassName;
    }
}