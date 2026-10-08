package com.open.spring.mvc.cspathway;

import java.util.List;

/** Advisory AI assessment of the evidence in a submitted Toolchain Trail report. */
public record CsPathwaySetupReportReview(
        String status,
        String summary,
        List<String> missingItems,
        List<String> warnings) {
}
