package com.open.spring.mvc.javarunner;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LocalJavaRunner {

    private static final long TIMEOUT_MS = 3000;

    public String run(Map<String, String> body) throws Exception {

        String code = body.get("code");

        if (code == null || code.isBlank()) {
            return "No code provided.";
        }

        Path tempDir = null;

        try {
            tempDir = Files.createTempDirectory("java-run-");

            String className = extractClassName(code);

            if (className == null) {
                return "No public class found in code.";
            }

            Path javaFile = tempDir.resolve(className + ".java");

            Files.writeString(
                    javaFile,
                    code,
                    StandardCharsets.UTF_8
            );

            // Compile
            Process compileProcess = new ProcessBuilder(
                    "javac",
                    javaFile.toString()
            )
                    .directory(tempDir.toFile())
                    .redirectErrorStream(true)
                    .start();

            boolean compileFinished = compileProcess.waitFor(
                    TIMEOUT_MS,
                    TimeUnit.MILLISECONDS
            );

            if (!compileFinished) {
                compileProcess.destroyForcibly();

                return "Compilation timed out.";
            }

            String compileOutput = new String(
                    compileProcess.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );

            if (compileProcess.exitValue() != 0) {
                return "Compilation error:\n" + compileOutput;
            }

            // Run
            Process runProcess = new ProcessBuilder(
                    "java",
                    "-cp",
                    tempDir.toString(),
                    className
            )
                    .directory(tempDir.toFile())
                    .redirectErrorStream(true)
                    .start();

            boolean finished = runProcess.waitFor(
                    TIMEOUT_MS,
                    TimeUnit.MILLISECONDS
            );

            if (!finished) {
                runProcess.destroyForcibly();

                return "Execution timed out ("
                        + (TIMEOUT_MS / 1000)
                        + "s limit).";
            }

            String output = new String(
                    runProcess.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );

            return output;

        } catch (Exception e) {

            return "Error running code: " + e.getMessage();

        } finally {

            if (tempDir != null) {
                cleanup(tempDir);
            }
        }
    }

    private String extractClassName(String code) {

        Pattern pattern = Pattern.compile(
                "public\\s+class\\s+(\\w+)"
        );

        Matcher matcher = pattern.matcher(code);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    private void cleanup(Path dir) {

        try {

            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {

                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }

                    });

        } catch (IOException ignored) {
        }
    }
}