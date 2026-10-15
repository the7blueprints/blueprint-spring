package com.open.spring.mvc.toolchain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Creates the Toolchain Trail session table for databases managed with ddl-auto=none. */
@Component
@RequiredArgsConstructor
public class ToolchainSessionMigration {

    private static final Logger logger = LoggerFactory.getLogger(ToolchainSessionMigration.class);

    private final JdbcTemplate jdbcTemplate;

    @Bean
    public ApplicationRunner migrateToolchainSession() {
        return args -> {
            try {
                // Unquoted lowercase names and plain types so the same statement runs on SQLite and MySQL.
                jdbcTemplate.execute(
                    "CREATE TABLE IF NOT EXISTS toolchain_session ("
                        + "id varchar(36) NOT NULL PRIMARY KEY, "
                        + "student_uid varchar(255) NOT NULL, "
                        + "station_id varchar(64) NOT NULL, "
                        + "pairing_code_hash varchar(64) UNIQUE, "
                        + "pairing_expires_at bigint, "
                        + "pairing_used_at bigint, "
                        + "agent_token_hash varchar(64) UNIQUE, "
                        + "agent_token_expires_at bigint, "
                        + "requested_at bigint, "
                        + "status varchar(32), "
                        + "agent_os varchar(16), "
                        + "results text, "
                        + "created_at bigint, "
                        + "updated_at bigint)");
            } catch (DataAccessException e) {
                logger.warn("Could not create Toolchain Trail session table: {}", e.getMessage());
            }
            try {
                // Tables created before one pairing covered the whole trail lack this column.
                jdbcTemplate.execute("ALTER TABLE toolchain_session ADD COLUMN requested_at bigint");
            } catch (DataAccessException e) {
                // Already there.
            }
            try {
                // The old PAIRED status is now CHECKING; keep agents paired before the upgrade working.
                jdbcTemplate.update("UPDATE toolchain_session SET status = 'CHECKING' WHERE status = 'PAIRED'");
            } catch (DataAccessException e) {
                logger.warn("Could not rename PAIRED Toolchain Trail sessions: {}", e.getMessage());
            }
        };
    }
}
