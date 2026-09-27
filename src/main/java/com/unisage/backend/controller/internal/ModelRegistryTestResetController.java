package com.unisage.backend.controller.internal;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.service.modelregistrytestreset.ModelRegistryTestResetService;

import lombok.RequiredArgsConstructor;

/**
 * {@code POST /internal/test/registry/reset} — todo.md Task 0.5, "Endpoint reset không bao giờ tồn
 * tại ngoài harness". Lives under {@code /internal/**} so it automatically gets the existing
 * secret + CIDR + no-store infrastructure ({@link com.unisage.backend.security.InternalSecretFilter},
 * {@link com.unisage.backend.security.InternalCallerCidrFilter},
 * {@link com.unisage.backend.security.InternalResponseHeadersFilter} all match on the path pattern,
 * not on this specific controller). Only ever registered under profile {@code integration}
 * (see {@link com.unisage.backend.config.ModelRegistryIntegrationProfileStartupCheck}) — outside
 * that profile this bean does not exist, so the path 404s at the DispatcherServlet before it ever
 * reaches this class, even with a valid secret and an allowed caller IP.
 */
@RestController
@RequestMapping("/internal/test/registry")
@Profile("integration")
@RequiredArgsConstructor
public class ModelRegistryTestResetController {

    private final ModelRegistryTestResetService resetService;

    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        resetService.reset();
        return ResponseEntity.noContent().build();
    }
}
