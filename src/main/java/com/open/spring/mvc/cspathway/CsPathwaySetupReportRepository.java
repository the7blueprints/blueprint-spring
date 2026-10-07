package com.open.spring.mvc.cspathway;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CsPathwaySetupReportRepository extends JpaRepository<CsPathwaySetupReport, String> {
    Optional<CsPathwaySetupReport> findByPairingCode(String pairingCode);

    boolean existsByPairingCode(String pairingCode);

    List<CsPathwaySetupReport> findAllByReportedAtIsNotNull();
}
