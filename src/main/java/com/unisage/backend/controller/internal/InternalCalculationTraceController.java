package com.unisage.backend.controller.internal;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.request.internal.CalculationTraceIngestRequest;
import com.unisage.backend.dto.response.internal.CalculationTraceIngestResponse;
import com.unisage.backend.service.calculation.CalculationTraceService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Staff-only calculation traces pushed by unisage-agent - SPEC-calculation-node §7.2(b). Same
 * security stack as every other {@code /internal/**} controller (secret + CIDR + no-store).
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalCalculationTraceController {

    private final CalculationTraceService calculationTraceService;

    @PostMapping("/calculation-traces")
    public CalculationTraceIngestResponse ingest(@Valid @RequestBody CalculationTraceIngestRequest request) {
        return calculationTraceService.ingest(request);
    }
}
