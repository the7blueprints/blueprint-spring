package com.open.spring.mvc.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.open.spring.mvc.toolchain.ToolchainController.CheckEvidence;
import com.open.spring.mvc.toolchain.ToolchainController.PairResponse;
import com.open.spring.mvc.toolchain.ToolchainController.ResultsRequest;
import com.open.spring.mvc.toolchain.ToolchainController.SessionView;
import com.open.spring.mvc.toolchain.ToolchainController.StartResponse;

class ToolchainServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-14T18:00:00Z");
    // Deliberately different from application.properties, to prove the service uses what it is given.
    private static final Duration PAIRING_CODE_LIFETIME = Duration.ofMinutes(7);
    private static final Duration AGENT_TOKEN_LIFETIME = Duration.ofHours(3);

    @Mock
    private ToolchainSessionRepository repository;

    /** A tiny in-memory table behind the mocked repository. */
    private final Map<String, ToolchainSession> rows = new HashMap<>();
    private MutableClock clock;
    private ToolchainService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        clock = new MutableClock(NOW);
        service = new ToolchainService(repository, new ToolchainStations(), clock, PAIRING_CODE_LIFETIME, AGENT_TOKEN_LIFETIME);

        when(repository.save(any(ToolchainSession.class))).thenAnswer(invocation -> {
            ToolchainSession row = invocation.getArgument(0);
            rows.put(row.getId(), row);
            return row;
        });
        when(repository.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(rows.get(invocation.<String>getArgument(0))));
        when(repository.findByPairingCodeHash(anyString())).thenAnswer(invocation -> rows.values().stream()
                .filter(row -> invocation.getArgument(0).equals(row.getPairingCodeHash())).findFirst());
        when(repository.consumePairingCode(anyString(), anyLong())).thenAnswer(invocation -> {
            String hash = invocation.getArgument(0);
            long now = invocation.getArgument(1);
            for (ToolchainSession row : rows.values()) {
                if (hash.equals(row.getPairingCodeHash()) && row.getPairingUsedAt() == null && row.getPairingExpiresAt() > now) {
                    row.setPairingUsedAt(now);
                    return 1;
                }
            }
            return 0;
        });
    }

    @Test
    void startStoresOnlyTheHashOfAFiveMinuteCode() {
        StartResponse started = service.start("toby", "git-village-hall");

        ToolchainSession row = rows.get(started.sessionId().toString());
        assertEquals("toby", row.getStudentUid());
        assertEquals("git-village-hall", row.getStationId());
        assertNotEquals(started.pairingCode(), row.getPairingCodeHash());
        assertEquals(ToolchainService.sha256(started.pairingCode()), row.getPairingCodeHash());
        assertEquals(NOW.plus(PAIRING_CODE_LIFETIME), started.expiresAt());
        assertTrue(started.pairingCode().length() >= 40);
    }

    @Test
    void startRejectsUnknownStations() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.start("toby", "moon-base"));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void pairingCodeWorksOnlyOnce() {
        StartResponse started = service.start("toby", "git-village-hall");

        PairResponse paired = service.pair(started.pairingCode());
        assertEquals(started.sessionId(), paired.sessionId());
        assertEquals("git-village-hall", paired.stationId());
        assertEquals(ToolchainSession.CHECKING, rows.get(started.sessionId().toString()).getStatus());

        ResponseStatusException again = assertThrows(ResponseStatusException.class, () -> service.pair(started.pairingCode()));
        assertEquals(HttpStatus.UNAUTHORIZED, again.getStatusCode());
    }

    @Test
    void expiredPairingCodeIsRejectedAndReportedAsExpired() {
        StartResponse started = service.start("toby", "git-village-hall");
        clock.advanceSeconds(PAIRING_CODE_LIFETIME.toSeconds() + 1);

        assertThrows(ResponseStatusException.class, () -> service.pair(started.pairingCode()));
        assertEquals(ToolchainSession.EXPIRED, service.getSession("toby", started.sessionId().toString()).status());
    }

    @Test
    void matchingEvidenceVerifiesTheStation() {
        StartResponse started = service.start("toby", "git-village-hall");
        PairResponse paired = service.pair(started.pairingCode());

        SessionView result = service.submitResults(started.sessionId().toString(), "Bearer " + paired.agentToken(),
                new ResultsRequest("windows", null, List.of(
                        new CheckEvidence("git", 0, "git version 2.46.0.windows.1"),
                        new CheckEvidence("git-name", 0, "Toby Ledger"),
                        new CheckEvidence("git-email", 0, "t***@opencodingsociety.com"))));

        assertEquals(ToolchainSession.VERIFIED, result.status());
        assertEquals(ToolchainSession.VERIFIED, service.getSession("toby", started.sessionId().toString()).status());
        assertEquals("windows", rows.get(started.sessionId().toString()).getAgentOs());
    }

    @Test
    void failingEvidenceMarksTheStationFailedWithFixes() {
        StartResponse started = service.start("toby", "git-village-hall");
        PairResponse paired = service.pair(started.pairingCode());

        SessionView result = service.submitResults(started.sessionId().toString(), "Bearer " + paired.agentToken(),
                new ResultsRequest("linux", null, List.of(
                        new CheckEvidence("git", 0, "git version 2.43.0"),
                        new CheckEvidence("git-name", 1, ""))));

        assertEquals(ToolchainSession.FAILED, result.status());
        assertEquals(3, result.checks().size());
        assertTrue(result.checks().get(0).passed());
        assertTrue(result.checks().get(1).detail().contains("user.name"));
    }

    @Test
    void agentTokenOnlyWorksForItsOwnSession() {
        StartResponse first = service.start("toby", "git-village-hall");
        StartResponse second = service.start("hope", "git-village-hall");
        PairResponse firstAgent = service.pair(first.pairingCode());
        service.pair(second.pairingCode());

        ResultsRequest request = new ResultsRequest("macos", null, List.of(new CheckEvidence("git", 0, "git version 2.39.5")));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.submitResults(second.sessionId().toString(), "Bearer " + firstAgent.agentToken(), request));
        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
    }

    @Test
    void agentTokenExpiresAfterItsConfiguredLifetime() {
        StartResponse started = service.start("toby", "git-village-hall");
        PairResponse paired = service.pair(started.pairingCode());
        clock.advanceSeconds(AGENT_TOKEN_LIFETIME.toSeconds() + 1);

        ResultsRequest request = new ResultsRequest("macos", null, List.of(new CheckEvidence("git", 0, "git version 2.39.5")));
        assertThrows(ResponseStatusException.class,
                () -> service.submitResults(started.sessionId().toString(), "Bearer " + paired.agentToken(), request));
    }

    @Test
    void missingOrMalformedAuthorizationIsRejected() {
        StartResponse started = service.start("toby", "git-village-hall");
        service.pair(started.pairingCode());
        ResultsRequest request = new ResultsRequest("macos", null, List.of(new CheckEvidence("git", 0, "git version 2.39.5")));

        assertThrows(ResponseStatusException.class, () -> service.submitResults(started.sessionId().toString(), null, request));
        assertThrows(ResponseStatusException.class, () -> service.submitResults(started.sessionId().toString(), "Basic abc", request));
        assertThrows(ResponseStatusException.class, () -> service.submitResults("not-a-uuid", "Bearer x", request));
    }

    @Test
    void studentsCannotReadEachOthersSessions() {
        StartResponse started = service.start("toby", "git-village-hall");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.getSession("hope", started.sessionId().toString()));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    @Test
    void verifiedStationIsNotUndoneByALaterRun() {
        StartResponse started = service.start("toby", "editor-isle-tower");
        PairResponse paired = service.pair(started.pairingCode());
        String auth = "Bearer " + paired.agentToken();
        String id = started.sessionId().toString();

        service.submitResults(id, auth, new ResultsRequest("linux", null, List.of(new CheckEvidence("editor", 0, "1.94.2\nabc123\nx64"))));
        SessionView again = service.submitResults(id, auth, new ResultsRequest("linux", null, List.of(new CheckEvidence("editor", 127, "command not found: code"))));

        assertEquals(ToolchainSession.VERIFIED, again.status());
    }

    @Test
    void onePairingCoversLaterStations() {
        StartResponse started = service.start("toby", "terminal-town-gate");
        PairResponse paired = service.pair(started.pairingCode());
        String id = started.sessionId().toString();
        String auth = "Bearer " + paired.agentToken();
        service.submitResults(id, auth, new ResultsRequest("linux", "terminal-town-gate", List.of(
                new CheckEvidence("working-folder", 0, "/home/toby"),
                new CheckEvidence("trail-folder", 0, "/home/toby/toolchain-trail"))));

        clock.advanceSeconds(60);
        SessionView requested = service.requestCheck("toby", id, "editor-isle-tower");
        assertEquals(ToolchainSession.CHECKING, requested.status());
        assertTrue(requested.checks().isEmpty());

        SessionView next = service.nextRequest(id, auth);
        assertEquals("editor-isle-tower", next.stationId());
        assertEquals(requested.requestedAt(), next.requestedAt());

        SessionView result = service.submitResults(id, auth, new ResultsRequest("linux", "editor-isle-tower",
                List.of(new CheckEvidence("editor", 0, "1.94.2"))));
        assertEquals(ToolchainSession.VERIFIED, result.status());
        assertEquals("editor-isle-tower", result.stationId());
    }

    @Test
    void resultsForAStationTheGameDidNotAskAboutAreRefused() {
        StartResponse started = service.start("toby", "terminal-town-gate");
        PairResponse paired = service.pair(started.pairingCode());
        String id = started.sessionId().toString();

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.submitResults(id, "Bearer " + paired.agentToken(), new ResultsRequest("linux", "editor-isle-tower",
                        List.of(new CheckEvidence("editor", 0, "1.94.2")))));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }

    @Test
    void requestingACheckNeedsAConnectedAgent() {
        StartResponse started = service.start("toby", "terminal-town-gate");
        String id = started.sessionId().toString();

        ResponseStatusException unpaired = assertThrows(ResponseStatusException.class,
                () -> service.requestCheck("toby", id, "editor-isle-tower"));
        assertEquals(HttpStatus.CONFLICT, unpaired.getStatusCode());

        service.pair(started.pairingCode());
        clock.advanceSeconds(AGENT_TOKEN_LIFETIME.toSeconds() + 1);
        assertEquals(ToolchainSession.AGENT_EXPIRED, service.getSession("toby", id).status());
        assertThrows(ResponseStatusException.class, () -> service.requestCheck("toby", id, "editor-isle-tower"));
    }

    @Test
    void studentsCannotPointAnotherStudentsAgent() {
        StartResponse started = service.start("toby", "terminal-town-gate");
        service.pair(started.pairingCode());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.requestCheck("hope", started.sessionId().toString(), "editor-isle-tower"));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
