package com.unisage.backend.scheduler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.unisage.backend.exception.AppException;
import com.unisage.backend.service.pricing.ModelPricingSyncService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class ModelPricingSyncJob {

    private final ModelPricingSyncService modelPricingSyncService;
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.model-pricing.sync-on-startup:true}")
    private boolean syncOnStartup;

    @Scheduled(cron = "${app.model-pricing.sync-cron:0 0 3 * * *}", zone = "${app.timezone:Asia/Ho_Chi_Minh}")
    public void syncPrices() {
        runSync();
    }

    /**
     * Fires once, right after the app finishes starting - a fresh install (empty
     * {@code model_prices} table) gets real prices immediately instead of every call showing
     * "chưa định giá" (only the {@code BUDGET_RESERVATION_FALLBACK_USD} estimate) until the
     * next 03:00 cron tick. Only syncs when the table is still empty - a system that already
     * has prices (synced earlier, or a manual override) is left untouched; the cron job and the
     * "Đồng bộ ngay" button own re-syncing after that.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnFirstRun() {
        if (!syncOnStartup) {
            return;
        }
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM model_prices", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        log.info("model_prices is empty - running an initial pricing sync on startup");
        runSync();
    }

    private void runSync() {
        try {
            modelPricingSyncService.sync();
        } catch (AppException exc) {
            // Already logged with the cause; current prices stay in effect until the next run.
            log.warn("model pricing sync failed");
        }
    }
}
