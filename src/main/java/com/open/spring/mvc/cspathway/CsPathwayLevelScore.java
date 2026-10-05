package com.open.spring.mvc.cspathway;

/**
 * One student's score for one CS Pathway level.
 * percentComplete is both the score and the progress (0-100).
 */
public record CsPathwayLevelScore(
        String levelKey,
        String levelName,
        double percentComplete,
        boolean finished) {
}
