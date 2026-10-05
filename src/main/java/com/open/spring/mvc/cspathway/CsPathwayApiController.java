package com.open.spring.mvc.cspathway;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CS Pathway game scores for the signed-in student.
 * The student is always taken from the JWT cookie, never from the request body,
 * so one student cannot write another student's scores.
 */
@RestController
@RequestMapping("/api/cs-pathway")
public class CsPathwayApiController {
    private final CsPathwayScoreService scoreService;

    public CsPathwayApiController(CsPathwayScoreService scoreService) {
        this.scoreService = scoreService;
    }

    /** GET /api/cs-pathway/scores - all five levels for the signed-in student. */
    @GetMapping("/scores")
    public ResponseEntity<?> getMyScores(@AuthenticationPrincipal UserDetails user) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Sign in to load scores."));
        }
        List<CsPathwayLevelScore> levels = scoreService.getScores(user.getUsername());
        return ResponseEntity.ok(Map.of("levels", levels));
    }

    /**
     * PUT /api/cs-pathway/scores/{levelKey}
     * Body: {"percentComplete": 60}
     */
    @PutMapping("/scores/{levelKey}")
    public ResponseEntity<?> recordMyProgress(
            @AuthenticationPrincipal UserDetails user,
            @PathVariable String levelKey,
            @RequestBody(required = false) CsPathwayProgressRequest request) {
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Sign in to save scores."));
        }
        CsPathwayLevel level = CsPathwayLevel.fromKey(levelKey).orElse(null);
        if (level == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown level: " + levelKey));
        }
        try {
            Double percent = request == null ? null : request.percentComplete();
            return ResponseEntity.ok(scoreService.recordProgress(user.getUsername(), level, percent));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
        }
    }
}
