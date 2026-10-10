package com.unisage.backend.service.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.unisage.backend.dto.response.DashboardSummaryResponse;
import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.entity.enums.UserStatus;
import com.unisage.backend.repository.DocumentRepository;
import com.unisage.backend.repository.MessageRepository;
import com.unisage.backend.repository.TicketRepository;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.service.health.SystemHealthCheckService;

class DashboardServiceImplTest {

    // 18:30 UTC on Oct 5 is already 01:30 on Oct 6 in Asia/Ho_Chi_Minh.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T18:30:00Z"), ZoneOffset.UTC);

    private final UserRepository userRepository = mock(UserRepository.class);
    private final MessageRepository messageRepository = mock(MessageRepository.class);

    private DashboardServiceImpl service() {
        DashboardServiceImpl service = new DashboardServiceImpl(
                userRepository,
                messageRepository,
                mock(DocumentRepository.class),
                mock(TicketRepository.class),
                mock(SystemHealthCheckService.class),
                CLOCK);
        ReflectionTestUtils.setField(service, "zoneId", ZoneId.of("Asia/Ho_Chi_Minh"));
        return service;
    }

    @Test
    void todayStartsAtLocalMidnightExpressedInUtc() {
        service().getSummary();

        // 00:00 Oct 6 (UTC+7) == 17:00 Oct 5 UTC.
        LocalDateTime startOfToday = LocalDateTime.of(2026, 10, 5, 17, 0);
        verify(userRepository).countByStatusAndLastLoginAfter(UserStatus.ACTIVE, startOfToday);
        verify(messageRepository).countByRoleAndCreatedAtBetween(
                MsgRole.ASSISTANT, startOfToday, LocalDateTime.of(2026, 10, 5, 18, 30));
    }

    @Test
    void weeklyActivityEndsOnTheLocalDayAndGroupsInAppZone() {
        when(messageRepository.countDailyAssistantMessagesSince(any(), any())).thenReturn(List.of());

        DashboardSummaryResponse summary = service().getSummary();

        verify(messageRepository).countDailyAssistantMessagesSince(
                eq(LocalDateTime.of(2026, 9, 29, 17, 0)), eq("Asia/Ho_Chi_Minh"));
        List<DashboardSummaryResponse.DailyActivity> daily = summary.weeklyActivity().daily();
        assertThat(daily).hasSize(7);
        assertThat(daily.get(daily.size() - 1).date()).isEqualTo(LocalDate.of(2026, 10, 6));
    }
}
