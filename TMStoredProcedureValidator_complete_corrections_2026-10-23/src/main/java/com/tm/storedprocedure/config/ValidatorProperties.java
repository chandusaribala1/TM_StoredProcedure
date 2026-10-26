package com.tm.storedprocedure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "validator")
public class ValidatorProperties {
    private boolean startupEnabled = true;
    private String database = "tm";
    private String schema = "public1";
    private String reportDirectory = "reports";
    private int maxRoutines = 500;
    private int maxTestsPerRoutine = 8;
    private int maxTestsPerRun = 200;
    private int maxGeneratedRowsPerRun = 200;
    private int maxSnapshotTables = 100;
    private int maxSnapshotRowsPerTable = 5000;
    private int maxOutputRows = 100;
    private int maxGeneratedStringLength = 64;
    private int queryTimeoutSeconds = 30;
    private int runTimeoutSeconds = 600;
    private String internalTransactionMode = "AUTOCOMMIT_BOUNDED";
    private boolean compatibilityViewsEnabled = true;
    private boolean allowWriteReplay = false;

    public boolean isStartupEnabled() { return startupEnabled; }
    public void setStartupEnabled(boolean value) { startupEnabled = value; }
    public String getDatabase() { return database; }
    public void setDatabase(String value) { database = value; }
    public String getSchema() { return schema; }
    public void setSchema(String value) { schema = value; }
    public String getReportDirectory() { return reportDirectory; }
    public void setReportDirectory(String value) { reportDirectory = value; }
    public int getMaxRoutines() { return maxRoutines; }
    public void setMaxRoutines(int value) { maxRoutines = bounded(value, 1, 10000); }
    public int getMaxTestsPerRoutine() { return maxTestsPerRoutine; }
    public void setMaxTestsPerRoutine(int value) { maxTestsPerRoutine = bounded(value, 1, 100); }
    public int getMaxTestsPerRun() { return maxTestsPerRun; }
    public void setMaxTestsPerRun(int value) { maxTestsPerRun = bounded(value, 1, 10000); }
    public int getMaxGeneratedRowsPerRun() { return maxGeneratedRowsPerRun; }
    public void setMaxGeneratedRowsPerRun(int value) { maxGeneratedRowsPerRun = bounded(value, 1, 10000); }
    public int getMaxSnapshotTables() { return maxSnapshotTables; }
    public void setMaxSnapshotTables(int value) { maxSnapshotTables = bounded(value, 1, 10000); }
    public int getMaxSnapshotRowsPerTable() { return maxSnapshotRowsPerTable; }
    public void setMaxSnapshotRowsPerTable(int value) { maxSnapshotRowsPerTable = bounded(value, 1, 1000000); }
    public int getMaxOutputRows() { return maxOutputRows; }
    public void setMaxOutputRows(int value) { maxOutputRows = bounded(value, 1, 10000); }
    public int getMaxGeneratedStringLength() { return maxGeneratedStringLength; }
    public void setMaxGeneratedStringLength(int value) { maxGeneratedStringLength = bounded(value, 8, 10000); }
    public int getQueryTimeoutSeconds() { return queryTimeoutSeconds; }
    public void setQueryTimeoutSeconds(int value) { queryTimeoutSeconds = bounded(value, 1, 3600); }
    public int getRunTimeoutSeconds() { return runTimeoutSeconds; }
    public void setRunTimeoutSeconds(int value) { runTimeoutSeconds = bounded(value, 1, 86400); }
    public String getInternalTransactionMode() { return internalTransactionMode; }
    public void setInternalTransactionMode(String value) { internalTransactionMode = value; }
    public boolean isCompatibilityViewsEnabled() { return compatibilityViewsEnabled; }
    public void setCompatibilityViewsEnabled(boolean value) { compatibilityViewsEnabled = value; }
    public boolean isAllowWriteReplay() { return allowWriteReplay; }
    public void setAllowWriteReplay(boolean value) { allowWriteReplay = value; }

    private static int bounded(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
