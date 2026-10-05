package com.open.spring.mvc.cspathway;

import java.util.List;

/**
 * One row of the teacher view: a student and their score for every level.
 */
public record CsPathwayStudentRow(
        String uid,
        String name,
        List<CsPathwayLevelScore> levels,
        double averagePercent) {
}
