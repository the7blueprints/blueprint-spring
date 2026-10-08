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

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/generate", exchange -> {
            byte[] request = exchange.getRequestBody().readAllBytes();
            String requestText = new String(request, StandardCharsets.UTF_8);
            if (requestText.isBlank()) {
                exchange.sendResponseHeaders(400, -1);
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
