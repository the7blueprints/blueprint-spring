package com.open.spring.mvc.cspathway;

/**
 * Response of POST /api/cs-pathway/setup-report/pairing-code.
 */
public record CsPathwayPairingCode(String code, long expiresAt) {
}
