package com.unisage.backend.service.backup;

/** A backup step failed; the message is stored on the {@code backup_runs} row. */
public class BackupException extends RuntimeException {

    public BackupException(String message) {
        super(message);
    }

    public BackupException(String message, Throwable cause) {
        super(message, cause);
    }
}
