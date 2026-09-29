package com.unisage.backend.scheduler;

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

    @Scheduled(cron = "${app.model-pricing.sync-cron:0 0 3 * * *}", zone = "${app.timezone:Asia/Ho_Chi_Minh}")
    public void syncPrices() {
        try {
            modelPricingSyncService.sync();
        } catch (AppException exc) {
            // Already logged with the cause; current prices stay in effect until the next run.
            log.warn("scheduled model pricing sync failed");
        }
    }
}
