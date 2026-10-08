package com.open.spring.mvc.javarunner;

import org.springframework.web.client.RestClient;

import io.github.cdimascio.dotenv.Dotenv;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/run")
public class JavaRunnerApiController {

    private final LocalJavaRunner localJavaRunner;
    private final RestClient restClient;
    private final boolean isProduction;
    private final String runnerUrl;

    public JavaRunnerApiController(RestClient.Builder builder, LocalJavaRunner localJavaRunner) {
        this.restClient = builder.build();
        this.localJavaRunner = localJavaRunner;

        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();

        String productionValue = System.getenv("IS_PRODUCTION");

        if (productionValue == null) {
                productionValue = dotenv.get(
                        "IS_PRODUCTION",
                        "false"
                );
        }

        this.isProduction = Boolean.parseBoolean(
                productionValue
        );

        String configuredRunnerUrl =
                System.getenv("JAVA_RUNNER_URL");

        if (configuredRunnerUrl == null) {
                configuredRunnerUrl = dotenv.get(
                        "JAVA_RUNNER_URL",
                        "http://code_runner:8592"
                );
        }

        this.runnerUrl = configuredRunnerUrl;

    }

    @PostMapping("/java")
    public ResponseEntity<Map<String, String>> runJava(
            @RequestBody Map<String, String> body) {

        if (isProduction) {
            return runInDocker(body);
        }

        return runLocally(body);
    }

    private ResponseEntity<Map<String, String>> runInDocker(
            Map<String, String> body) {

        try {
            Map<String, String> response =
                    restClient.post()
                            .uri(runnerUrl + "/java")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body)
                            .retrieve()
                            .body(new ParameterizedTypeReference<>() {});

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of(
                            "output",
                            "Could not connect to Java runner: "
                                    + e.getMessage()
                    ));
        }
    }

    private ResponseEntity<Map<String, String>> runLocally(
            Map<String, String> body) {

        try {
            String output = localJavaRunner.run(body);

            return ResponseEntity.ok(Map.of(
                    "output",
                    output
            ));

        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of(
                            "output",
                            "Could not run Java locally: "
                                    + e.getMessage()
                    ));
        }
    }
}