package com.open.spring.mvc.cspathway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Creates the setup-report table for databases managed with ddl-auto=none. */
@Component
@RequiredArgsConstructor
public class CsPathwaySetupReportMigration {

    private static final Logger logger = LoggerFactory.getLogger(CsPathwaySetupReportMigration.class);

    private final JdbcTemplate jdbcTemplate;

    @Bean
    public ApplicationRunner migrateCsPathwaySetupReport() {
        return args -> {
            try {
                // Unquoted lowercase names and plain types so the same statement runs on SQLite and MySQL.
                jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS cs_pathway_setup_report ("
                        + "uid varchar(255) NOT NULL PRIMARY KEY, "
                        + "pairing_code varchar(16) UNIQUE, "
                        + "pairing_code_expires_at bigint, "
                        + "overall varchar(8), "
                        + "passed integer, "
                        + "warned integer, "
                        + "failed integer, "
                        + "report text, "
                        + "reported_at bigint)");
            } catch (DataAccessException e) {
                logger.warn("Could not create CS Pathway setup-report table: {}", e.getMessage());
            }
        };
    }
}
