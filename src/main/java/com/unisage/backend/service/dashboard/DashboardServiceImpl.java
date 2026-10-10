package com.unisage.backend.service.dashboard;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.unisage.backend.dto.response.DashboardSummaryResponse;
import com.unisage.backend.dto.response.DashboardSummaryResponse.AiAnswerStats;
import com.unisage.backend.dto.response.DashboardSummaryResponse.DailyActivity;
import com.unisage.backend.dto.response.DashboardSummaryResponse.DocumentStats;
import com.unisage.backend.dto.response.DashboardSummaryResponse.TicketStats;
import com.unisage.backend.dto.response.DashboardSummaryResponse.UserStats;
import com.unisage.backend.dto.response.DashboardSummaryResponse.WeeklyActivity;
import com.unisage.backend.entity.enums.DocStatus;
import com.unisage.backend.entity.enums.MsgRole;
import com.unisage.backend.entity.enums.TicketStatus;
import com.unisage.backend.entity.enums.UserStatus;
import com.unisage.backend.repository.DocumentRepository;
import com.unisage.backend.repository.MessageRepository;
import com.unisage.backend.repository.TicketRepository;
import com.unisage.backend.repository.UserRepository;
import com.unisage.backend.repository.projection.DailyMessageCount;
import com.unisage.backend.service.health.SystemHealthCheckService;

import lombok.RequiredArgsConstructor;

/**
 * UNISAGE-72. Every tile is a direct count/aggregate query against data this backend already
 * owns (users, messages, documents, tickets) plus the existing live health check
 * ({@link SystemHealthCheckService#checkNow()}) - nothing here is cached or persisted, so the
 * dashboard is always current as of the request.
 */
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private static final int WEEKLY_ACTIVITY_DAYS = 7;

    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final DocumentRepository documentRepository;
    private final TicketRepository ticketRepository;
    private final SystemHealthCheckService systemHealthCheckService;
    private final Clock clock;

    // "Today"/"this week" are calendar days in app.timezone; the DB columns they filter are UTC.
    @Value("${app.timezone:Asia/Ho_Chi_Minh}")
    private ZoneId zoneId;

    @Override
    public DashboardSummaryResponse getSummary() {
        LocalDateTime startOfToday = startOfDayUtc(today());
        LocalDateTime now = LocalDateTime.now(clock);

        return DashboardSummaryResponse.builder()
                .users(buildUserStats(startOfToday))
                .aiAnswers(buildAiAnswerStats(startOfToday, now))
                .documents(buildDocumentStats())
                .tickets(buildTicketStats())
                .weeklyActivity(buildWeeklyActivity())
                .health(systemHealthCheckService.checkNow())
                .build();
    }

    private UserStats buildUserStats(LocalDateTime startOfToday) {
        long activeTotal = userRepository.countByStatus(UserStatus.ACTIVE);
        long activeToday = userRepository.countByStatusAndLastLoginAfter(UserStatus.ACTIVE, startOfToday);
        return UserStats.builder().activeTotal(activeTotal).activeToday(activeToday).build();
    }

    private AiAnswerStats buildAiAnswerStats(LocalDateTime startOfToday, LocalDateTime now) {
        long today = messageRepository.countByRoleAndCreatedAtBetween(MsgRole.ASSISTANT, startOfToday, now);
        if (today == 0) {
            return AiAnswerStats.builder().today(0).citedPercentage(null).build();
        }
        long cited = messageRepository.countAssistantWithCitationsBetween(startOfToday, now);
        double citedPercentage = Math.round(cited * 1000.0 / today) / 10.0;
        return AiAnswerStats.builder().today(today).citedPercentage(citedPercentage).build();
    }

    private DocumentStats buildDocumentStats() {
        long published = documentRepository.countByStatusAndDeletedAtIsNull(DocStatus.COMPLETED);
        long departments = documentRepository.countDistinctDepartmentsByStatus(DocStatus.COMPLETED);
        return DocumentStats.builder().published(published).departments(departments).build();
    }

    private TicketStats buildTicketStats() {
        return TicketStats.builder().open(ticketRepository.countByStatus(TicketStatus.OPEN)).build();
    }

    private WeeklyActivity buildWeeklyActivity() {
        LocalDate today = today();
        LocalDate from = today.minusDays(WEEKLY_ACTIVITY_DAYS - 1L);
        Map<LocalDate, Long> countsByDay = messageRepository
                .countDailyAssistantMessagesSince(startOfDayUtc(from), zoneId.getId())
                .stream()
                .collect(Collectors.toMap(DailyMessageCount::getDay, DailyMessageCount::getCount));

        List<DailyActivity> daily = new ArrayList<>();
        long total = 0;
        for (LocalDate date = from; !date.isAfter(today); date = date.plusDays(1)) {
            long count = countsByDay.getOrDefault(date, 0L);
            daily.add(DailyActivity.builder().date(date).questions(count).build());
            total += count;
        }
        return WeeklyActivity.builder().daily(daily).totalQuestions(total).build();
    }

    private LocalDate today() {
        return ZonedDateTime.now(clock).withZoneSameInstant(zoneId).toLocalDate();
    }

    private LocalDateTime startOfDayUtc(LocalDate day) {
        return day.atStartOfDay(zoneId).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
