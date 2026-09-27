package com.unisage.backend.contracts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisage.backend.entity.enums.ChatModelVerificationStatus;

/**
 * {@code contracts/verification-statuses.json} is generated, never hand-edited — this is the only
 * place allowed to write it. Source of truth is {@link ChatModelVerificationStatus}'s declaration
 * order (plan.md "Verification lifecycle", table "Trạng thái verification"). CI runs this test
 * and then {@code git diff --exit-code contracts/} — an enum change that isn't reflected here fails
 * the pipeline instead of silently drifting from what {@code unisage-web}/{@code unisage-agent} sync.
 */
class ContractExportTest {

    private static final Path CONTRACT_PATH = Path.of("contracts", "verification-statuses.json");

    @Test
    void exportVerificationStatuses() throws IOException {
        List<String> values = Arrays.stream(ChatModelVerificationStatus.values())
                .map(Enum::name)
                .toList();

        String json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(values);
        Files.writeString(CONTRACT_PATH, json + System.lineSeparator(), StandardCharsets.UTF_8);
    }
}
