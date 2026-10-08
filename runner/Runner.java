import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Runner {

    private static final int PORT = 8592;
    private static final long TIMEOUT_MS = 3000;

    public static void main(String[] args) throws Exception {

        HttpServer server = HttpServer.create(
                new InetSocketAddress("0.0.0.0", PORT),
                0
        );

        server.createContext("/java", Runner::runJava);

        server.setExecutor(null);

        System.out.println("Java runner listening on port " + PORT);

        server.start();
    }

    private static void runJava(HttpExchange exchange) throws IOException {

        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendResponse(exchange, 405, "Method not allowed");
            return;
        }

        String requestBody = new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
        );

        String code = extractCode(requestBody);

        if (code == null || code.isBlank()) {
            sendResponse(exchange, 400, "{\"output\":\"No code provided.\"}");
            return;
        }

        Path tempDir = null;

        try {
            tempDir = Files.createTempDirectory("java-run-");

            String className = extractClassName(code);

            if (className == null) {
                sendResponse(
                        exchange,
                        400,
                        "{\"output\":\"No public class found in code.\"}"
                );
                return;
            }

            Path javaFile = tempDir.resolve(className + ".java");

            Files.writeString(javaFile, code);

            // Compile
            Process compileProcess = new ProcessBuilder(
                    "javac",
                    javaFile.toString()
            )
                    .directory(tempDir.toFile())
                    .redirectErrorStream(true)
                    .start();

            boolean compileFinished =
                    compileProcess.waitFor(
                            TIMEOUT_MS,
                            TimeUnit.MILLISECONDS
                    );

            String compileOutput =
                    new String(
                            compileProcess.getInputStream().readAllBytes(),
                            StandardCharsets.UTF_8
                    );

            if (!compileFinished) {
                compileProcess.destroyForcibly();

                sendResponse(
                        exchange,
                        200,
                        "{\"output\":\"Compilation timed out.\"}"
                );

                return;
            }

            if (compileProcess.exitValue() != 0) {
                sendResponse(
                        exchange,
                        200,
                        jsonOutput("Compilation error:\n" + compileOutput)
                );

                return;
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

            boolean finished =
                    runProcess.waitFor(
                            TIMEOUT_MS,
                            TimeUnit.MILLISECONDS
                    );

            String output =
                    new String(
                            runProcess.getInputStream().readAllBytes(),
                            StandardCharsets.UTF_8
                    );

            if (!finished) {
                runProcess.destroyForcibly();

                sendResponse(
                        exchange,
                        200,
                        jsonOutput(
                                "Execution timed out (" +
                                (TIMEOUT_MS / 1000) +
                                "s limit)."
                        )
                );

                return;
            }

            sendResponse(
                    exchange,
                    200,
                    jsonOutput(output)
            );

        } catch (Exception e) {

            sendResponse(
                    exchange,
                    500,
                    jsonOutput(
                            "Error running code: " + e.getMessage()
                    )
            );

        } finally {

            if (tempDir != null) {
                cleanup(tempDir);
            }
        }
    }

    private static String extractClassName(String code) {

        Pattern pattern = Pattern.compile(
                "public\\s+class\\s+(\\w+)"
        );

        Matcher matcher = pattern.matcher(code);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    private static String extractCode(String json) {

        // Minimal JSON extraction for {"code":"..."}.
        // Replace with a proper JSON parser if desired.
        int start = json.indexOf("\"code\"");

        if (start == -1) {
            return null;
        }

        start = json.indexOf(':', start);

        if (start == -1) {
            return null;
        }

        start++;

        while (start < json.length() &&
                Character.isWhitespace(json.charAt(start))) {
            start++;
        }

        if (start >= json.length() || json.charAt(start) != '"') {
            return null;
        }

        start++;

        StringBuilder result = new StringBuilder();

        boolean escaped = false;

        for (int i = start; i < json.length(); i++) {

            char c = json.charAt(i);

            if (escaped) {
                switch (c) {
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    default -> result.append(c);
                }

                escaped = false;

            } else if (c == '\\') {

                escaped = true;

            } else if (c == '"') {

                return result.toString();

            } else {

                result.append(c);
            }
        }

        return null;
    }

    private static String jsonOutput(String output) {

        String escaped = output
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");

        return "{\"output\":\"" + escaped + "\"}";
    }

    private static void sendResponse(
            HttpExchange exchange,
            int status,
            String body
    ) throws IOException {

        byte[] response =
                body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders()
                .set("Content-Type", "application/json");

        exchange.sendResponseHeaders(
                status,
                response.length
        );

        try (OutputStream output =
                     exchange.getResponseBody()) {

            output.write(response);
        }
    }

    private static void cleanup(Path dir) {

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
