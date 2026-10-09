package com.open.spring.mvc.cspathway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CsPathwaySetupReportReviewServiceTest {
    private final CsPathwaySetupReportService reportService = mock(CsPathwaySetupReportService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private String geminiResponse;
    // HTTP statuses to answer with before the normal 200 response, one per request.
    private final Deque<Integer> failuresBeforeSuccess = new ArrayDeque<>();
    private int requestCount;
    private String lastRequestBody;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/generate", exchange -> {
            requestCount++;
            byte[] request = exchange.getRequestBody().readAllBytes();
            String requestText = new String(request, StandardCharsets.UTF_8);
            lastRequestBody = requestText;
            if (requestText.isBlank()) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            Integer failure = failuresBeforeSuccess.poll();
            if (failure != null) {
                exchange.sendResponseHeaders(failure, -1);
                exchange.close();
                return;
            }
            byte[] response = geminiResponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void reviewReturnsAiAssessmentAndNeverCallsAReportWithFailedChecksComplete() {
        CsPathwaySetupReport report = report("FAIL", 14, 1, 2);
        when(reportService.findReport("toby")).thenReturn(Optional.of(report));
        geminiResponse = """
                {"candidates":[{"content":{"parts":[{"text":"{\\"status\\":\\"COMPLETE\\",\\"summary\\":\\"Everything is ready.\\",\\"missingItems\\":[],\\"warnings\\":[]}"}]}}]}
                """;

        CsPathwaySetupReportReview result = reviewService().review("toby");

        assertEquals("INCOMPLETE", result.status());
        assertTrue(result.summary().contains("setup is not complete"));
        assertEquals("ruby installed", result.missingItems().get(0));
    }

    @Test
    void reviewParsesMissingChecksFromAiResponseForAnOtherwisePassingReport() {
        CsPathwaySetupReport report = report("PASS", 17, 0, 0);
        when(reportService.findReport("toby")).thenReturn(Optional.of(report));
        geminiResponse = """
                {"candidates":[{"content":{"parts":[{"text":"{\\"status\\":\\"REVIEW_NEEDED\\",\\"summary\\":\\"The report is ambiguous.\\",\\"missingItems\\":[\\"editor check\\"],\\"warnings\\":[\\"The output is self-reported.\\"]}"}]}}]}
                """;

        CsPathwaySetupReportReview result = reviewService().review("toby");

        assertEquals("REVIEW_NEEDED", result.status());
        assertEquals("editor check", result.missingItems().get(0));
        assertEquals("The output is self-reported.", result.warnings().get(0));
    }

    @Test
    void reviewRetriesWhenGeminiIsBusyAndSkipsThoughtParts() {
        when(reportService.findReport("toby")).thenReturn(Optional.of(report("PASS", 17, 0, 0)));
        failuresBeforeSuccess.add(503);
        geminiResponse = """
                {"candidates":[{"content":{"parts":[{"thought":true,"text":"thinking..."},{"text":"{\\"status\\":\\"COMPLETE\\",\\"summary\\":\\"All checks pass.\\",\\"missingItems\\":[],\\"warnings\\":[]}"}]}}]}
                """;

        CsPathwaySetupReportReview result = reviewService().review("toby");

        assertEquals("COMPLETE", result.status());
        assertEquals(2, requestCount);
    }

    @Test
    void reviewExplainsWhyGeminiFailedInsteadOfAGenericError() {
        when(reportService.findReport("toby")).thenReturn(Optional.of(report("PASS", 17, 0, 0)));
        failuresBeforeSuccess.add(403);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> reviewService().review("toby"));

        assertTrue(error.getMessage().contains("refused the API key"));
        assertEquals(1, requestCount); // a rejected key is not worth retrying
    }

    @Test
    void reviewAsksGeminiForJsonModeWithTheReviewSchema() {
        when(reportService.findReport("toby")).thenReturn(Optional.of(report("PASS", 17, 0, 0)));
        geminiResponse = """
                {"candidates":[{"content":{"parts":[{"text":"{\\"status\\":\\"COMPLETE\\",\\"summary\\":\\"All checks pass.\\",\\"missingItems\\":[],\\"warnings\\":[]}"}]}}]}
                """;

        reviewService().review("toby");

        assertTrue(lastRequestBody.contains("\"responseMimeType\":\"application/json\""));
        assertTrue(lastRequestBody.contains("\"responseSchema\""));
    }

    @Test
    void reviewAcceptsAnAnswerWrappedInProseAndAFenceWithALowercaseStatus() {
        when(reportService.findReport("toby")).thenReturn(Optional.of(report("PASS", 17, 0, 0)));
        geminiResponse = """
                {"candidates":[{"content":{"parts":[{"text":"Here is the review:\\n```json\\n{\\"status\\":\\"complete\\",\\"summary\\":\\"All checks pass.\\",\\"missingItems\\":[],\\"warnings\\":[]}\\n```"}]}}]}
                """;

        assertEquals("COMPLETE", reviewService().review("toby").status());
    }

    @Test
    void reviewRejectsAnAnswerMissingRequiredFields() {
        when(reportService.findReport("toby")).thenReturn(Optional.of(report("PASS", 17, 0, 0)));
        geminiResponse = """
                {"candidates":[{"content":{"parts":[{"text":"{\\"status\\":\\"COMPLETE\\"}"}]}}]}
                """;

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> reviewService().review("toby"));
        assertEquals("The AI returned an invalid review format.", error.getMessage());
    }

    @Test
    void reviewFailsExplicitlyWhenStudentHasNoReport() {
        when(reportService.findReport("toby")).thenReturn(Optional.empty());

        assertThrows(java.util.NoSuchElementException.class, () -> reviewService().review("toby"));
        verify(reportService).findReport("toby");
    }

    private CsPathwaySetupReportReviewService reviewService() {
        String apiUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/generate";
        return new CsPathwaySetupReportReviewService(reportService, objectMapper, "test-key", apiUrl);
    }

    private CsPathwaySetupReport report(String overall, int passed, int warned, int failed) {
        CsPathwaySetupReport report = new CsPathwaySetupReport("toby");
        report.setOverall(overall);
        report.setPassed(passed);
        report.setWarned(warned);
        report.setFailed(failed);
        report.setReport((warned > 0 ? "[WARN] jupyter available\n" : "")
                + (failed > 0 ? "[FAIL] ruby installed\n" : "")
                + "Summary: " + passed + " passed, " + warned + " warned, " + failed + " failed\n"
                + "Overall: " + overall + "\n");
        report.setReportedAt(1L);
        return report;
    }
}
