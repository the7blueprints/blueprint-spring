package com.open.spring.mvc.cspathway;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Toolchain Trail setup reports sent from a student's Terminal.
 * The game page (JWT cookie) asks for a pairing code and reads the report back;
 * the upload itself is public because a Terminal has no cookie, so the pairing
 * code is what ties it to a student. See SecurityConfig for the access rules.
 */
@RestController
@RequestMapping("/api/cs-pathway/setup-report")
public class CsPathwaySetupReportApiController {
    private final CsPathwaySetupReportService reportService;
    private final CsPathwaySetupReportReviewService reviewService;

    public CsPathwaySetupReportApiController(
            CsPathwaySetupReportService reportService,
            CsPathwaySetupReportReviewService reviewService) {
        this.reportService = reportService;
        this.reviewService = reviewService;
    }

    /** POST /api/cs-pathway/setup-report/pairing-code - a code for the signed-in student's verify command. */
    @PostMapping("/pairing-code")
    public ResponseEntity<?> issuePairingCode(@AuthenticationPrincipal UserDetails user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Sign in to send setup results."));
        }
        return ResponseEntity.ok(reportService.issuePairingCode(user.getUsername()));
    }

    /**
     * POST /api/cs-pathway/setup-report/{pairingCode}
     * Body: the plain-text output of scripts/verifyToolsTerminal.sh.
     */
    @PostMapping(value = "/{pairingCode}", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<?> uploadReport(@PathVariable String pairingCode,
            @RequestBody(required = false) String reportText) {
        try {
            if (!reportService.saveReport(pairingCode, reportText)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "This code is unknown or expired. Copy the verify command from the game again."));
            }
            return ResponseEntity.ok(Map.of("saved", true));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
        }
    }

    /** GET /api/cs-pathway/setup-report - the signed-in student's latest report ({"report": null} if none). */
    @GetMapping
    public ResponseEntity<?> getMyReport(@AuthenticationPrincipal UserDetails user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Sign in to load setup results."));
        }
        Map<String, Object> body = new LinkedHashMap<>(); // Map.of rejects the null "no report yet" values
        CsPathwaySetupReport report = reportService.findReport(user.getUsername()).orElse(null);
        body.put("report", report == null ? null : report.getReport());
        body.put("overall", report == null ? null : report.getOverall());
        body.put("reportedAt", report == null ? null : report.getReportedAt());
        return ResponseEntity.ok(body);
    }

    /** POST /api/cs-pathway/setup-report/review/{uid} - AI review for admin/teacher use. */
    @PostMapping("/review/{uid}")
    public ResponseEntity<?> reviewReport(@PathVariable String uid) {
        try {
            return ResponseEntity.ok(reviewService.review(uid));
        } catch (NoSuchElementException error) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", error.getMessage()));
        } catch (IllegalStateException error) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", error.getMessage()));
        }
    }
}
