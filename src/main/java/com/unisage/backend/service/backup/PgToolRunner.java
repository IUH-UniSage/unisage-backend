package com.unisage.backend.service.backup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Runs a PostgreSQL client tool (pg_basebackup) against the app database. */
@Component
@RequiredArgsConstructor
public class PgToolRunner {

    private static final int ERROR_TAIL_CHARS = 2000;

    private final BackupProperties properties;

    /**
     * Runs {@code tool} with the connection flags plus {@code args}. The password goes through
     * PGPASSWORD, never the command line, so it does not show up in {@code ps}.
     */
    public void run(String tool, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(resolve(tool));
        command.addAll(List.of("-h", properties.getDbHost(), "-p", String.valueOf(properties.getDbPort()),
                "-U", properties.getDbUser(), "-w"));
        command.addAll(args);

        Path log = null;
        try {
            Files.createDirectories(properties.getWorkDir());
            log = Files.createTempFile(properties.getWorkDir(), tool, ".log");
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            builder.environment().put("PGPASSWORD", properties.getDbPassword());
            Process process = builder.start();
            if (!process.waitFor(properties.getPgTimeoutMinutes(), TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new BackupException(tool + " timed out after " + properties.getPgTimeoutMinutes() + " min");
            }
            if (process.exitValue() != 0) {
                throw new BackupException(tool + " exited with " + process.exitValue() + ": " + tail(log));
            }
        } catch (IOException e) {
            throw new BackupException("cannot run " + tool, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackupException(tool + " was interrupted", e);
        } finally {
            WorkDirs.deleteQuietly(log);
        }
    }

    private String resolve(String tool) {
        String binDir = properties.getPgBinDir();
        return binDir == null || binDir.isBlank() ? tool : Path.of(binDir, tool).toString();
    }

    private static String tail(Path log) throws IOException {
        String output = Files.readString(log, StandardCharsets.UTF_8).strip();
        return output.length() <= ERROR_TAIL_CHARS ? output : output.substring(output.length() - ERROR_TAIL_CHARS);
    }
}
