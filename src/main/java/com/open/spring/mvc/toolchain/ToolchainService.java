package com.open.spring.mvc.toolchain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.open.spring.mvc.toolchain.ToolchainController.PairResponse;
import com.open.spring.mvc.toolchain.ToolchainController.ResultsRequest;
import com.open.spring.mvc.toolchain.ToolchainController.SessionView;
import com.open.spring.mvc.toolchain.ToolchainController.StartResponse;
import com.open.spring.mvc.toolchain.ToolchainStations.CheckResult;
import com.open.spring.mvc.toolchain.ToolchainStations.Evidence;

/**
 * One-time device pairing for Toolchain Trail. One pairing covers the whole trail.
 *
 * 1. The signed-in game starts a session at its first station and gets a single-use pairing code.
 * 2. The local agent exchanges that code once for an agent token scoped to that student's session.
 * How long each lasts is set in application.properties (toolchain.pairing-code-lifetime, toolchain.agent-token-lifetime).
 * 3. At each station the game asks for a check; the agent sees the request, runs that station's
 *    commands, and sends the results with "Authorization: Bearer <agent-token>".
 *    The server only accepts results for the station the game asked about and decides pass/fail.
 * 4. The game polls the session until the station is verified or failed.
 */
@Service
public class ToolchainService {
    static final int MAX_CHECKS = 20;
    static final int MAX_OUTPUT_LENGTH = 4000;
    private static final List<String> KNOWN_OS = List.of("macos", "windows", "linux", "wsl");
    private static final String BAD_PAIRING_CODE = "Pairing code is invalid, expired, or already used";
    private static final String BAD_AGENT_TOKEN = "Agent token is invalid or expired. Get a new pairing code in the game.";
    private static final String AGENT_NOT_CONNECTED = "No agent is connected to this session. Get a new pairing code.";

    private static final Logger logger = LoggerFactory.getLogger(ToolchainService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolchainSessionRepository repository;
    private final ToolchainStations stationRules;
    private final Clock clock;
    private final Duration pairingCodeLifetime;
    private final Duration agentTokenLifetime;

    @Autowired
    public ToolchainService(ToolchainSessionRepository repository, ToolchainStations stationRules,
            @Value("${toolchain.pairing-code-lifetime}") Duration pairingCodeLifetime,
            @Value("${toolchain.agent-token-lifetime}") Duration agentTokenLifetime) {
        this(repository, stationRules, Clock.systemUTC(), pairingCodeLifetime, agentTokenLifetime);
    }

    ToolchainService(ToolchainSessionRepository repository, ToolchainStations stationRules, Clock clock,
            Duration pairingCodeLifetime, Duration agentTokenLifetime) {
        this.repository = repository;
        this.stationRules = stationRules;
        this.clock = clock;
        this.pairingCodeLifetime = pairingCodeLifetime;
        this.agentTokenLifetime = agentTokenLifetime;
    }

    public StartResponse start(String studentId, String stationId) {
        if (studentId == null || studentId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in to start a station.");
        }
        stationRules.requireAccessible(studentId, stationId);

        String code = randomToken();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(pairingCodeLifetime);

        ToolchainSession session = new ToolchainSession();
        session.setId(UUID.randomUUID().toString());
        session.setStudentUid(studentId);
        session.setStationId(stationId);
        session.setPairingCodeHash(sha256(code));
        session.setPairingExpiresAt(expiresAt.toEpochMilli());
        session.setRequestedAt(now.toEpochMilli());
        session.setStatus(ToolchainSession.WAITING_FOR_AGENT);
        session.setCreatedAt(now.toEpochMilli());
        session.setUpdatedAt(now.toEpochMilli());
        repository.save(session);

        return new StartResponse(UUID.fromString(session.getId()), stationId, code, expiresAt);
    }

    public PairResponse pair(String code) {
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, BAD_PAIRING_CODE);
        }
        String codeHash = sha256(code.strip());
        Instant now = clock.instant();
        // Atomically consume only an unused, unexpired code.
        if (repository.consumePairingCode(codeHash, now.toEpochMilli()) != 1) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, BAD_PAIRING_CODE);
        }
        ToolchainSession session = repository.findByPairingCodeHash(codeHash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, BAD_PAIRING_CODE));

        String agentToken = randomToken();
        Instant expiresAt = now.plus(agentTokenLifetime);
        session.setAgentTokenHash(sha256(agentToken));
        session.setAgentTokenExpiresAt(expiresAt.toEpochMilli());
        session.setStatus(ToolchainSession.CHECKING); // the station the session was started at
        session.setUpdatedAt(now.toEpochMilli());
        repository.save(session);

        return new PairResponse(UUID.fromString(session.getId()), session.getStationId(), agentToken, expiresAt);
    }

    /**
     * The game reached another station (or wants a re-check): point the paired agent at it.
     * Fails with 409 when no agent is connected, so the game can show a new pairing code.
     */
    public SessionView requestCheck(String studentId, String sessionId, String stationId) {
        ToolchainSession session = requireOwnSession(studentId, sessionId);
        stationRules.requireAccessible(studentId, stationId);
        if (!agentConnected(session)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, AGENT_NOT_CONNECTED);
        }
        boolean alreadyVerified = stationId.equals(session.getStationId())
                && ToolchainSession.VERIFIED.equals(session.getStatus());
        if (alreadyVerified) {
            return view(session); // nothing to re-check
        }
        session.setStationId(stationId);
        session.setRequestedAt(clock.millis());
        session.setStatus(ToolchainSession.CHECKING);
        session.setResults(null);
        session.setUpdatedAt(clock.millis());
        repository.save(session);
        return view(session);
    }

    /** The agent's poll: which station the game wants checked, and when it asked. */
    public SessionView nextRequest(String sessionId, String authorization) {
        return view(requireAgentSession(sessionId, authorization));
    }

    /** Checks the agent's evidence for its own session and stores the outcome. */
    public SessionView submitResults(String sessionId, String authorization, ResultsRequest request) {
        ToolchainSession session = requireAgentSession(sessionId, authorization);
        boolean namesAStation = request != null && request.stationId() != null;
        boolean forCurrentStation = namesAStation && request.stationId().equals(session.getStationId());
        if (namesAStation && !forCurrentStation) {
            // The student moved on in the game while the agent was running checks.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The game is now asking about a different station.");
        }
        if (request == null || request.checks() == null || request.checks().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No check results were sent.");
        }
        if (request.checks().size() > MAX_CHECKS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many check results.");
        }
        // A verified station stays verified; running the agent again cannot undo it.
        if (ToolchainSession.VERIFIED.equals(session.getStatus())) {
            return view(session);
        }
        List<Evidence> evidence = request.checks().stream()
                .map(item -> item == null ? null : new Evidence(item.id(), item.exitCode(), truncate(item.output())))
                .toList();
        List<CheckResult> results = stationRules.evaluate(session.getStationId(), evidence);
        boolean verified = !results.isEmpty() && results.stream().allMatch(CheckResult::passed);
        session.setStatus(verified ? ToolchainSession.VERIFIED : ToolchainSession.FAILED);
        session.setAgentOs(KNOWN_OS.contains(request.os()) ? request.os() : null);
        session.setResults(writeResults(results));
        session.setUpdatedAt(clock.millis());
        repository.save(session);
        logger.info("Toolchain Trail {} for {}: {}", session.getStationId(), session.getStudentUid(), session.getStatus());
        return view(session);
    }

    /** The game's poll: only the student who started the session can read it. */
    public SessionView getSession(String studentId, String sessionId) {
        return view(requireOwnSession(studentId, sessionId));
    }

    private ToolchainSession requireOwnSession(String studentId, String sessionId) {
        return findSession(sessionId)
                .filter(found -> found.getStudentUid().equals(studentId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found."));
    }

    private boolean agentConnected(ToolchainSession session) {
        return session.getAgentTokenHash() != null
                && session.getAgentTokenExpiresAt() != null
                && session.getAgentTokenExpiresAt() > clock.millis();
    }

    private ToolchainSession requireAgentSession(String sessionId, String authorization) {
        String token = bearerToken(authorization);
        if (token == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, BAD_AGENT_TOKEN);
        }
        ToolchainSession session = findSession(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, BAD_AGENT_TOKEN));
        // The token is scoped: it only matches the hash stored on the session it was issued for.
        boolean matches = session.getAgentTokenHash() != null
                && MessageDigest.isEqual(
                        session.getAgentTokenHash().getBytes(StandardCharsets.US_ASCII),
                        sha256(token).getBytes(StandardCharsets.US_ASCII));
        boolean unexpired = session.getAgentTokenExpiresAt() != null && session.getAgentTokenExpiresAt() > clock.millis();
        if (!(matches && unexpired)) { // only a matching, unexpired token gets in
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, BAD_AGENT_TOKEN);
        }
        return session;
    }

    private Optional<ToolchainSession> findSession(String sessionId) {
        try {
            return repository.findById(UUID.fromString(sessionId).toString());
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    private SessionView view(ToolchainSession session) {
        String status = session.getStatus();
        boolean waitingForAgent = ToolchainSession.WAITING_FOR_AGENT.equals(status);
        if (waitingForAgent) {
            // Not paired yet: the code runs out after its lifetime.
            boolean codeExpired = session.getPairingExpiresAt() <= clock.millis();
            if (codeExpired) status = ToolchainSession.EXPIRED;
        } else if (!agentConnected(session)) {
            // Paired before, but the agent's token has run out.
            status = ToolchainSession.AGENT_EXPIRED;
        }
        return new SessionView(
                UUID.fromString(session.getId()),
                session.getStationId(),
                session.getRequestedAt(),
                status,
                readResults(session.getResults()),
                Instant.ofEpochMilli(session.getPairingExpiresAt()),
                session.getUpdatedAt() == null ? null : Instant.ofEpochMilli(session.getUpdatedAt()));
    }

    static String bearerToken(String authorization) {
        if (authorization == null) return null;
        String value = authorization.strip();
        if (value.length() < 8 || !value.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String token = value.substring(7).strip();
        return token.isEmpty() ? null : token;
    }

    private static String truncate(String output) {
        if (output == null) return "";
        return output.length() > MAX_OUTPUT_LENGTH ? output.substring(0, MAX_OUTPUT_LENGTH) : output;
    }

    private static String writeResults(List<CheckResult> results) {
        try {
            return JSON.writeValueAsString(results);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store check results", e);
        }
    }

    private static List<CheckResult> readResults(String results) {
        if (results == null || results.isBlank()) return List.of();
        try {
            return JSON.readValue(results, new TypeReference<List<CheckResult>>() {});
        } catch (JsonProcessingException e) {
            logger.warn("Unreadable Toolchain Trail results: {}", e.getMessage());
            return List.of();
        }
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
