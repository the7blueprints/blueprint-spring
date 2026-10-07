package com.open.spring.mvc.cspathway;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Lets a student's Terminal upload its Toolchain Trail setup report.
 * The Terminal has no login cookie, so the signed-in game page asks for a
 * short-lived pairing code and the verify script sends that code with the report.
 * Reports are self-reported output: they show setup gaps, they are not proof.
 */
@Service
public class CsPathwaySetupReportService {
    static final Duration PAIRING_CODE_LIFETIME = Duration.ofMinutes(30);
    static final int MAX_REPORT_LENGTH = 20000; // same cap as the game's paste box
    static final int PAIRING_CODE_LENGTH = 8;
    // No 0/O or 1/I/L, so a code read off the screen cannot be mistyped.
    private static final String PAIRING_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final Pattern PAIRING_CODE_FORMAT =
            Pattern.compile("[" + PAIRING_CODE_ALPHABET + "]{" + PAIRING_CODE_LENGTH + "}");

    // Must match what scripts/verifyToolsTerminal.sh prints in the pages repository.
    private static final Pattern OVERALL_LINE = Pattern.compile("(?m)^Overall:\\s*(PASS|WARN|FAIL)\\s*$");
    private static final Pattern SUMMARY_LINE =
            Pattern.compile("(?m)^Summary:\\s*(\\d{1,4}) passed, (\\d{1,4}) warned, (\\d{1,4}) failed\\s*$");

    private static final Logger logger = LoggerFactory.getLogger(CsPathwaySetupReportService.class);

    private final CsPathwaySetupReportRepository repository;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public CsPathwaySetupReportService(CsPathwaySetupReportRepository repository) {
        this(repository, Clock.systemUTC());
    }

    CsPathwaySetupReportService(CsPathwaySetupReportRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Gives the student a code to put in their verify command.
     * An unexpired code is kept and extended, so a command the student already
     * copied keeps working when they reopen the panel.
     */
    public CsPathwayPairingCode issuePairingCode(String uid) {
        requireUid(uid);
        long now = clock.millis();
        CsPathwaySetupReport row = repository.findById(uid).orElseGet(() -> new CsPathwaySetupReport(uid));
        if (!isPairingCodeActive(row, now)) {
            row.setPairingCode(newUnusedPairingCode());
        }
        row.setPairingCodeExpiresAt(now + PAIRING_CODE_LIFETIME.toMillis());
        repository.save(row);
        return new CsPathwayPairingCode(row.getPairingCode(), row.getPairingCodeExpiresAt());
    }

    /**
     * Saves a report uploaded with a pairing code, replacing the student's earlier report.
     * The code stays valid until it expires so the student can fix things and run the verifier again.
     *
     * @return false when the code is unknown or expired
     * @throws IllegalArgumentException when the text is not verifier output
     */
    public boolean saveReport(String pairingCode, String reportText) {
        if (pairingCode == null || !PAIRING_CODE_FORMAT.matcher(pairingCode).matches()) {
            return false;
        }
        if (reportText == null || reportText.isBlank()) {
            throw new IllegalArgumentException("The report is empty.");
        }
        if (reportText.length() > MAX_REPORT_LENGTH) {
            throw new IllegalArgumentException("The report is longer than " + MAX_REPORT_LENGTH + " characters.");
        }
        Matcher overall = OVERALL_LINE.matcher(reportText);
        Matcher summary = SUMMARY_LINE.matcher(reportText);
        if (!overall.find() || !summary.find()) {
            throw new IllegalArgumentException("No verification results found in the report.");
        }

        long now = clock.millis();
        CsPathwaySetupReport row = repository.findByPairingCode(pairingCode).orElse(null);
        if (row == null || !isPairingCodeActive(row, now)) {
            return false;
        }
        row.setOverall(overall.group(1));
        row.setPassed(Integer.valueOf(summary.group(1)));
        row.setWarned(Integer.valueOf(summary.group(2)));
        row.setFailed(Integer.valueOf(summary.group(3)));
        row.setReport(reportText);
        row.setReportedAt(now);
        repository.save(row);
        logger.info("CS Pathway setup report saved for {}: {}", row.getUid(), row.getOverall());
        return true;
    }

    /** The student's latest report; empty until their Terminal has uploaded one. */
    public Optional<CsPathwaySetupReport> findReport(String uid) {
        requireUid(uid);
        return repository.findById(uid).filter(CsPathwaySetupReport::hasReport);
    }

    /** Latest report of every student who has uploaded one, keyed by uid, for the teacher view. */
    public Map<String, CsPathwaySetupReport> getReportsByUid() {
        List<CsPathwaySetupReport> reports = repository.findAllByReportedAtIsNotNull();
        return reports.stream().collect(Collectors.toMap(CsPathwaySetupReport::getUid, Function.identity()));
    }

    private static boolean isPairingCodeActive(CsPathwaySetupReport row, long now) {
        return row.getPairingCode() != null
                && row.getPairingCodeExpiresAt() != null
                && row.getPairingCodeExpiresAt() > now;
    }

    private String newUnusedPairingCode() {
        String code;
        do {
            StringBuilder builder = new StringBuilder(PAIRING_CODE_LENGTH);
            for (int i = 0; i < PAIRING_CODE_LENGTH; i++) {
                builder.append(PAIRING_CODE_ALPHABET.charAt(random.nextInt(PAIRING_CODE_ALPHABET.length())));
            }
            code = builder.toString();
        } while (repository.existsByPairingCode(code));
        return code;
    }

    private static void requireUid(String uid) {
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("Student uid is required.");
        }
    }
}
