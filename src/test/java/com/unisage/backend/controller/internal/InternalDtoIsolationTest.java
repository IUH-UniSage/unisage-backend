package com.unisage.backend.controller.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Source-scan: no SA-facing controller may import the internal DTO packages. */
class InternalDtoIsolationTest {

    private static final Path CONTROLLER_ROOT = Path.of("src/main/java/com/unisage/backend/controller");
    private static final Path INTERNAL_CONTROLLER_DIR = CONTROLLER_ROOT.resolve("internal");

    @Test
    void saFacingControllers_neverImportInternalDtoPackages() throws IOException {
        List<Path> violators;
        try (Stream<Path> files = Files.walk(CONTROLLER_ROOT)) {
            violators = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.startsWith(INTERNAL_CONTROLLER_DIR))
                    .filter(this::importsInternalDto)
                    .toList();
        }

        assertThat(violators).as("SA-facing controllers importing dto.*.internal").isEmpty();
    }

    private boolean importsInternalDto(Path file) {
        try {
            String content = Files.readString(file);
            return content.contains("dto.request.internal") || content.contains("dto.response.internal");
        } catch (IOException e) {
            throw new UncheckedIOExceptionForTest(e);
        }
    }

    private static class UncheckedIOExceptionForTest extends RuntimeException {
        UncheckedIOExceptionForTest(IOException cause) {
            super(cause);
        }
    }
}
