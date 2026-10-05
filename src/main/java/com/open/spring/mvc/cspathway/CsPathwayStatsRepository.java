package com.open.spring.mvc.cspathway;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.open.spring.mvc.stats.Stats;

/**
 * CS Pathway queries over the shared stats table.
 * Kept separate from StatsRepository so the stats module stays untouched.
 */
public interface CsPathwayStatsRepository extends JpaRepository<Stats, Long> {
    List<Stats> findAllByModule(String module);

    List<Stats> findAllByUsernameAndModule(String username, String module);

    Optional<Stats> findByUsernameAndModuleAndSubmodule(String username, String module, int submodule);
}
