package com.unisage.backend.repository.projection;

import java.time.LocalDate;

/** One day's assistant-message count, for {@code MessageRepository}'s dashboard trend query. */
public interface DailyMessageCount {
    LocalDate getDay();
    Long getCount();
}
