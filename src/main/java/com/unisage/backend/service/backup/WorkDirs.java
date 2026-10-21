package com.unisage.backend.service.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

final class WorkDirs {

    private WorkDirs() {
    }

    /** Best effort: a leftover scratch file or folder only costs disk until the next cleanup. */
    static void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // See above.
        }
    }
}
