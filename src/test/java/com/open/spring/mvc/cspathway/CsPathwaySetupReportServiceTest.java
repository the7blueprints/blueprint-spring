package com.open.spring.mvc.cspathway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class CsPathwaySetupReportServiceTest {
    private static final String REPORT = """
            Environment verification for /Users/toby/blueprints_pages
            [PASS] git installed
            Summary: 14 passed, 1 warned, 2 failed
            Overall: FAIL

            Warnings and failures
            [WARN] jupyter available
            [FAIL] ruby installed
            """;

    @Mock
    private CsPathwaySetupReportRepository repository;

    private MutableClock clock;
    private CsPathwaySetupReportService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        clock = new MutableClock(Instant.parse("2026-10-06T16:00:00Z"));
        service = new CsPathwaySetupReportService(repository, clock);
        when(repository.save(any(CsPathwaySetupReport.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void issuePairingCodeGivesANewStudentAnEightCharacterCodeThatExpiresInThirtyMinutes() {
        when(repository.findById("toby")).thenReturn(Optional.empty());

        CsPathwayPairingCode pairingCode = service.issuePairingCode("toby");

        assertTrue(pairingCode.code().matches("[A-HJKMNP-Z2-9]{8}"));
        assertEquals(clock.millis() + Duration.ofMinutes(30).toMillis(), pairingCode.expiresAt());
    }

    @Test
    void issuePairingCodeKeepsAnUnexpiredCodeSoACopiedCommandStillWorks() {
        CsPathwaySetupReport row = pairedRow("toby", "ABCD2345");
        when(repository.findById("toby")).thenReturn(Optional.of(row));
        clock.advance(Duration.ofMinutes(10));

        CsPathwayPairingCode pairingCode = service.issuePairingCode("toby");

        assertEquals("ABCD2345", pairingCode.code());
        assertEquals(clock.millis() + Duration.ofMinutes(30).toMillis(), pairingCode.expiresAt());
    }

    @Test
    void issuePairingCodeReplacesAnExpiredCode() {
        CsPathwaySetupReport row = pairedRow("toby", "ABCD2345");
        when(repository.findById("toby")).thenReturn(Optional.of(row));
        clock.advance(Duration.ofMinutes(31));

        assertNotEquals("ABCD2345", service.issuePairingCode("toby").code());
    }

    @Test
    void saveReportStoresTheReportAndItsCountsForTheStudentWhoOwnsTheCode() {
        CsPathwaySetupReport row = pairedRow("toby", "ABCD2345");
        when(repository.findByPairingCode("ABCD2345")).thenReturn(Optional.of(row));

        assertTrue(service.saveReport("ABCD2345", REPORT));

        assertEquals("toby", row.getUid());
        assertEquals("FAIL", row.getOverall());
        assertEquals(14, row.getPassed());
        assertEquals(1, row.getWarned());
        assertEquals(2, row.getFailed());
        assertEquals(REPORT, row.getReport());
        assertEquals(clock.millis(), row.getReportedAt());
    }

    @Test
    void saveReportAcceptsASecondRunWithTheSameCode() {
        CsPathwaySetupReport row = pairedRow("toby", "ABCD2345");
        when(repository.findByPairingCode("ABCD2345")).thenReturn(Optional.of(row));
        service.saveReport("ABCD2345", REPORT);

        String fixed = "[PASS] ruby installed\nSummary: 17 passed, 0 warned, 0 failed\nOverall: PASS\n";
        assertTrue(service.saveReport("ABCD2345", fixed));

        assertEquals("PASS", row.getOverall());
        assertEquals(0, row.getFailed());
    }

    @Test
    void saveReportRefusesUnknownExpiredAndMalformedCodes() {
        CsPathwaySetupReport row = pairedRow("toby", "ABCD2345");
        when(repository.findByPairingCode("ABCD2345")).thenReturn(Optional.of(row));
        when(repository.findByPairingCode("ZZZZ9999")).thenReturn(Optional.empty());

        assertFalse(service.saveReport("ZZZZ9999", REPORT));
        assertFalse(service.saveReport("abcd2345", REPORT));
        assertFalse(service.saveReport(null, REPORT));
        clock.advance(Duration.ofMinutes(31));
        assertFalse(service.saveReport("ABCD2345", REPORT));

        verify(repository, never()).save(any(CsPathwaySetupReport.class));
    }

    @Test
    void saveReportRejectsTextThatIsNotVerifierOutput() {
        String tooLong = REPORT + "x".repeat(CsPathwaySetupReportService.MAX_REPORT_LENGTH);

        assertThrows(IllegalArgumentException.class, () -> service.saveReport("ABCD2345", ""));
        assertThrows(IllegalArgumentException.class, () -> service.saveReport("ABCD2345", "[PASS] git installed"));
        assertThrows(IllegalArgumentException.class, () -> service.saveReport("ABCD2345", tooLong));
        verify(repository, never()).save(any(CsPathwaySetupReport.class));
    }

    @Test
    void findReportIsEmptyUntilTheTerminalHasUploadedOne() {
        when(repository.findById("toby")).thenReturn(Optional.of(pairedRow("toby", "ABCD2345")));

        assertTrue(service.findReport("toby").isEmpty());
    }

    private CsPathwaySetupReport pairedRow(String uid, String code) {
        CsPathwaySetupReport row = new CsPathwaySetupReport(uid);
        row.setPairingCode(code);
        row.setPairingCodeExpiresAt(clock.millis() + CsPathwaySetupReportService.PAIRING_CODE_LIFETIME.toMillis());
        return row;
    }

    /** A clock the test moves by hand, so expiry is deterministic. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
