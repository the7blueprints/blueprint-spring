package com.open.spring.mvc.cspathway;

import java.util.Date;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row per student: the pairing code their Terminal uses to upload a
 * Toolchain Trail setup report, and the latest report it sent.
 * Times are epoch milliseconds so the same column type works on SQLite and MySQL.
 * The table is created by CsPathwaySetupReportMigration (ddl-auto is none).
 */
@Entity
@Table(name = "cs_pathway_setup_report")
@Data
@NoArgsConstructor
public class CsPathwaySetupReport {

    @Id
    private String uid;

    private String pairingCode;
    private Long pairingCodeExpiresAt;

    private String overall; // PASS, WARN, or FAIL; null until a report arrives
    private Integer passed;
    private Integer warned;
    private Integer failed;
    private String report; // raw verifyToolsTerminal.sh output
    private Long reportedAt;

    public CsPathwaySetupReport(String uid) {
        this.uid = uid;
    }

    public boolean hasReport() {
        return reportedAt != null;
    }

    /** For the teacher page's date formatting. */
    public Date getReportedAtDate() {
        return reportedAt == null ? null : new Date(reportedAt);
    }
}
