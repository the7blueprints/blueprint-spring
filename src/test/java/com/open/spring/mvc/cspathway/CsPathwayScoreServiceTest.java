package com.open.spring.mvc.cspathway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.open.spring.mvc.person.Person;
import com.open.spring.mvc.person.PersonJpaRepository;
import com.open.spring.mvc.stats.Stats;

class CsPathwayScoreServiceTest {
    private static final String MODULE = CsPathwayScoreService.MODULE;

    @Mock
    private CsPathwayStatsRepository statsRepository;

    @Mock
    private PersonJpaRepository personRepository;

    private CsPathwayScoreService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new CsPathwayScoreService(statsRepository, personRepository);
        when(statsRepository.save(any(Stats.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void getScoresReturnsAllFiveLevelsInPlayOrderWithUnplayedLevelsAtZero() {
        when(statsRepository.findAllByUsernameAndModule("toby", MODULE))
                .thenReturn(List.of(stats("toby", 2, 75.0, false)));

        List<CsPathwayLevelScore> scores = service.getScores("toby");

        assertEquals(5, scores.size());
        assertEquals("identity-forge", scores.get(0).levelKey());
        assertEquals(0.0, scores.get(0).percentComplete());
        assertEquals("mission-tools", scores.get(2).levelKey());
        assertEquals(75.0, scores.get(2).percentComplete());
        assertEquals("toolchain-trail", scores.get(4).levelKey());
    }

    @Test
    void recordProgressCreatesRowForFirstProgressOnALevel() {
        when(statsRepository.findByUsernameAndModuleAndSubmodule("toby", MODULE, 1)).thenReturn(Optional.empty());

        CsPathwayLevelScore score = service.recordProgress("toby", CsPathwayLevel.WAYFINDING_WORLD, 40.0);

        assertEquals(40.0, score.percentComplete());
        assertFalse(score.finished());
    }

    @Test
    void recordProgressNeverLowersAnEarlierScore() {
        when(statsRepository.findByUsernameAndModuleAndSubmodule("toby", MODULE, 1))
                .thenReturn(Optional.of(stats("toby", 1, 80.0, false)));

        CsPathwayLevelScore score = service.recordProgress("toby", CsPathwayLevel.WAYFINDING_WORLD, 20.0);

        assertEquals(80.0, score.percentComplete());
    }

    @Test
    void recordProgressMarksLevelFinishedAtOneHundredPercent() {
        when(statsRepository.findByUsernameAndModuleAndSubmodule("toby", MODULE, 4)).thenReturn(Optional.empty());

        CsPathwayLevelScore score = service.recordProgress("toby", CsPathwayLevel.TOOLCHAIN_TRAIL, 100.0);

        assertTrue(score.finished());
    }

    @Test
    void recordProgressRejectsPercentOutsideZeroToOneHundred() {
        assertThrows(IllegalArgumentException.class,
                () -> service.recordProgress("toby", CsPathwayLevel.MISSION_TOOLS, 101.0));
        assertThrows(IllegalArgumentException.class,
                () -> service.recordProgress("toby", CsPathwayLevel.MISSION_TOOLS, -1.0));
        assertThrows(IllegalArgumentException.class,
                () -> service.recordProgress("toby", CsPathwayLevel.MISSION_TOOLS, null));
        verify(statsRepository, never()).save(any(Stats.class));
    }

    @Test
    void getStudentRowsShowsStudentNamesSortedWithAverageAcrossAllLevels() {
        when(statsRepository.findAllByModule(MODULE)).thenReturn(List.of(
                stats("zed", 0, 100.0, true),
                stats("amy", 0, 50.0, false),
                stats("amy", 4, 100.0, true)));
        when(personRepository.findByUid("amy")).thenReturn(person("Amy Adams"));
        when(personRepository.findByUid("zed")).thenReturn(null);

        List<CsPathwayStudentRow> rows = service.getStudentRows();

        assertEquals(2, rows.size());
        assertEquals("Amy Adams", rows.get(0).name());
        assertEquals(30.0, rows.get(0).averagePercent()); // (50 + 0 + 0 + 0 + 100) / 5
        assertEquals("zed", rows.get(1).name()); // falls back to uid when no person exists
    }

    private static Stats stats(String uid, int submodule, Double percent, boolean finished) {
        Stats stats = new Stats();
        stats.setUsername(uid);
        stats.setModule(MODULE);
        stats.setSubmodule(submodule);
        stats.setGrades(percent);
        stats.setFinished(finished);
        return stats;
    }

    private static Person person(String name) {
        Person person = new Person();
        person.setName(name);
        return person;
    }
}
