package com.open.spring.mvc.cspathway;

import java.util.Arrays;
import java.util.Optional;

/**
 * The five CS Pathway game levels, in play order.
 * The submodule number is the level's row key in the shared stats table
 * (module = "cs-pathway"), so it must never be renumbered once data exists.
 */
public enum CsPathwayLevel {
    IDENTITY_FORGE("identity-forge", 0, "Identity Forge"),
    WAYFINDING_WORLD("wayfinding-world", 1, "Wayfinding World"),
    MISSION_TOOLS("mission-tools", 2, "Mission Tools"),
    ASSESSMENT_OBSERVATORY("assessment-observatory", 3, "Assessment Observatory"),
    TOOLCHAIN_TRAIL("toolchain-trail", 4, "Toolchain Trail");

    private final String key;
    private final int submodule;
    private final String displayName;

    CsPathwayLevel(String key, int submodule, String displayName) {
        this.key = key;
        this.submodule = submodule;
        this.displayName = displayName;
    }

    public String getKey() {
        return key;
    }

    public int getSubmodule() {
        return submodule;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static Optional<CsPathwayLevel> fromKey(String key) {
        return Arrays.stream(values()).filter(level -> level.key.equals(key)).findFirst();
    }
}
