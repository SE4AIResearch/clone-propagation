package com.cloneguard.refactor;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEnumerator;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs a REAL compile of a single file, by invoking the project's OWN
 * configured JDK's javac directly as an external process -- not the
 * project's build tool (gradlew/mvn), and not an in-process compiler API.
 *
 * THIRD approach tried tonight, replacing the second:
 *   1. In-process javax.tools.JavaCompiler -- confirmed dead: not
 *      available inside the plugin sandbox (IntelliJ's own bundled JBR
 *      doesn't expose it to plugin code).
 *   2. Shelling out to the project's gradlew/mvn -- worked, but required
 *      every project to already have a working Gradle/Maven setup,
 *      which a bare IntelliJ-only test project (no gradlew, no pom.xml)
 *      doesn't have. Confirmed live: correctly reported "no build tool
 *      found" rather than crashing, but that's a real usability gap --
 *      this needs to work with ZERO setup, the same way Scan Current
 *      File already does.
 *   3. THIS approach: every Java project opened in IntelliJ already has
 *      a configured JDK (Project Structure -> SDK) -- IntelliJ needs it
 *      to run/debug the project regardless of build tool, so it's
 *      ALWAYS present for any project someone would actually be using
 *      CloneGuard on. Finding javac inside that SDK and the module's
 *      classpath (via OrderEnumerator, which IntelliJ already tracks)
 *      needs no setup step from the user at all -- genuinely universal,
 *      matching the "works like Scan Current File" expectation.
 */
public final class RealCompilationChecker {

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final long TIMEOUT_SECONDS = 60;

    private RealCompilationChecker() {}

    public static class CompileOutcome {
        public final boolean success;
        public final String output;
        public final String errorDetail; // null if success

        CompileOutcome(boolean success, String output, String errorDetail) {
            this.success = success;
            this.output = output;
            this.errorDetail = errorDetail;
        }
    }

    /**
     * Compiles targetFile using the JDK and classpath of the module it
     * belongs to. Runs on a background thread -- never blocks the
     * caller. Output .class files go to a disposable temp directory,
     * never touching the project's real build output.
     */
    public static CompletableFuture<CompileOutcome> compileFileAsync(Project project, VirtualFile targetFile) {
        return CompletableFuture.supplyAsync(() -> runCompile(project, targetFile), EXECUTOR);
    }

    private static CompileOutcome runCompile(Project project, VirtualFile targetFile) {
        Module module = ModuleUtilCore.findModuleForFile(targetFile, project);
        if (module == null) {
            return new CompileOutcome(false, "", "Could not determine the module for this file.");
        }

        Sdk sdk = ModuleRootManager.getInstance(module).getSdk();
        if (sdk == null) {
            return new CompileOutcome(false, "",
                    "No JDK is configured for this module (Project Structure -> SDKs) -- cannot run a real compile check.");
        }

        String sdkHome = sdk.getHomePath();
        if (sdkHome == null) {
            return new CompileOutcome(false, "", "The configured JDK's home path could not be determined.");
        }

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        File javacFile = new File(sdkHome, "bin" + File.separator + (isWindows ? "javac.exe" : "javac"));
        if (!javacFile.exists()) {
            return new CompileOutcome(false, "",
                    "Could not find javac inside the configured JDK at " + javacFile.getAbsolutePath() +
                    " -- is this a full JDK, not just a JRE?");
        }

        // Classpath: everything the module depends on, PLUS its own
        // already-compiled output -- so sibling classes in the same
        // project resolve correctly, exactly as a real build would see
        // them. IntelliJ already tracks all of this; no manual setup
        // needed.
        String classpath = OrderEnumerator.orderEntries(module).recursively().getPathsList().getPathsString();

        Path tempOutputDir;
        try {
            tempOutputDir = Files.createTempDirectory("cloneguard-compile-");
        } catch (Exception e) {
            return new CompileOutcome(false, "", "Could not create a temp output directory: " + e.getMessage());
        }

        try {
            List<String> command = new ArrayList<>();
            command.add(javacFile.getAbsolutePath());
            if (!classpath.isBlank()) {
                command.add("-cp");
                command.add(classpath);
            }
            command.add("-d");
            command.add(tempOutputDir.toString());
            command.add(targetFile.getPath());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CompileOutcome(false, output.toString(),
                        "Compile timed out after " + TIMEOUT_SECONDS + " seconds.");
            }

            int exitCode = process.exitValue();
            return new CompileOutcome(exitCode == 0, output.toString(),
                    exitCode == 0 ? null : "javac exited with code " + exitCode);
        } catch (Exception e) {
            return new CompileOutcome(false, "", "Failed to run javac: " + e.getMessage());
        } finally {
            deleteRecursively(tempOutputDir.toFile());
        }
    }

    private static void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        f.delete();
    }

    public enum BeforeAfterVerdict {
        BOTH_COMPILED,
        BROKEN_BEFORE_ALREADY,
        REFACTOR_BROKE_IT,
        COULD_NOT_VERIFY
    }

    public static CompletableFuture<BeforeAfterVerdict> compareBeforeAndAfter(
            Project project, VirtualFile targetFile, boolean compiledSuccessfullyBefore) {
        return compileFileAsync(project, targetFile).thenApply(after -> {
            boolean couldNotRunAtAll = !after.success && after.errorDetail != null
                    && (after.errorDetail.startsWith("Could not determine the module")
                        || after.errorDetail.startsWith("No JDK is configured")
                        || after.errorDetail.startsWith("The configured JDK's home path")
                        || after.errorDetail.startsWith("Could not find javac"));
            if (couldNotRunAtAll) {
                return BeforeAfterVerdict.COULD_NOT_VERIFY;
            }
            if (!compiledSuccessfullyBefore) {
                return BeforeAfterVerdict.BROKEN_BEFORE_ALREADY;
            }
            return after.success ? BeforeAfterVerdict.BOTH_COMPILED : BeforeAfterVerdict.REFACTOR_BROKE_IT;
        });
    }
}