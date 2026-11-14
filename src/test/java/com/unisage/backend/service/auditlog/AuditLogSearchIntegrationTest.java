package com.unisage.backend.service.auditlog;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import com.unisage.backend.dto.response.AuditLogResponse;
import com.unisage.backend.entity.AuditLog;
import com.unisage.backend.entity.enums.AuditAction;
import com.unisage.backend.entity.enums.ResourceType;
import com.unisage.backend.repository.AuditLogRepository;
import com.unisage.backend.support.PostgresIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the shared SearchPatterns filter (unaccent, ESCAPE) runs on real Postgres. */
class AuditLogSearchIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private AuditLogService auditLogService;

    @AfterEach
    void cleanUp() {
        auditLogRepository.deleteAll();
    }

    @Test
    void actorCodeSearchIgnoresCaseAndAccentsAndTreatsWildcardsLiterally() {
        save("Đức_Admin");
        save("ducXadmin");
        save("other");

        assertThat(codes(" duc_admin ")).containsExactly("Đức_Admin");
        assertThat(codes("DUC")).containsExactlyInAnyOrder("Đức_Admin", "ducXadmin");
        assertThat(codes("%")).isEmpty();
    }

    private List<String> codes(String actorCode) {
        return auditLogService.search(null, null, null, actorCode, null, null, PageRequest.of(0, 20))
                .data().stream().map(AuditLogResponse::actorCode).toList();
    }

    private void save(String actorCode) {
        auditLogRepository.save(AuditLog.builder()
                .actorCode(actorCode)
                .action(AuditAction.CREATE)
                .resourceType(ResourceType.USER)
                .createdAt(LocalDateTime.now())
                .build());
    }
}
