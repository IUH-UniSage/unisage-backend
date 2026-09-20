package com.unisage.backend.service.health;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisage.backend.dto.response.ComponentHealthResponse;
import com.unisage.backend.dto.response.HealthCheckResponse;
import com.unisage.backend.dto.response.PageResponse;
import com.unisage.backend.entity.SystemHealthCheck;
import com.unisage.backend.repository.SystemHealthCheckRepository;

import jakarta.persistence.criteria.Predicate;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * UNISAGE-62. The "run every indicator and build the aggregate" logic lives here in exactly one
 * place ({@link #checkNow()}), built on Actuator's {@code HealthEndpoint} (which aggregates the
 * auto-configured {@code DataSourceHealthIndicator} plus the custom gateway/agent/minio
 * indicators) rather than a hand-rolled poller. Both {@code GET /admin/health} (live, no
 * persistence) and the scheduled history job ({@link #runAndPersist()}) call this same method —
 * no duplicated aggregation logic.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SystemHealthCheckServiceImpl implements SystemHealthCheckService {

    private static final TypeReference<Map<String, ComponentHealthResponse>> COMPONENTS_TYPE =
            new TypeReference<>() {};

    private final HealthEndpoint healthEndpoint;
    private final SystemHealthCheckRepository systemHealthCheckRepository;
    private final ObjectMapper objectMapper;

    @Override
    public HealthCheckResponse checkNow() {
        HealthComponent root = healthEndpoint.health();
        return HealthCheckResponse.builder()
                .overallStatus(root.getStatus().getCode())
                .checkedAt(LocalDateTime.now())
                .components(flatten(root))
                .build();
    }

    @Override
    @Transactional
    public void runAndPersist() {
        HealthCheckResponse result = checkNow();
        try {
            SystemHealthCheck entity = SystemHealthCheck.builder()
                    .checkedAt(result.checkedAt())
                    .overallStatus(result.overallStatus())
                    .componentsJson(objectMapper.writeValueAsString(result.components()))
                    .build();
            systemHealthCheckRepository.save(entity);
        } catch (Exception e) {
            // A serialization/persistence hiccup here must not take down the scheduler thread.
            log.error("Failed to persist system health-check result", e);
        }
    }

    @Override
    public PageResponse<List<HealthCheckResponse>> getHistory(
            LocalDateTime from, LocalDateTime to, Pageable pageable) {
        Page<SystemHealthCheck> page = systemHealthCheckRepository.findAll(buildSpec(from, to), pageable);
        return PageResponse.fromPage(page, this::toResponse);
    }

    private Specification<SystemHealthCheck> buildSpec(LocalDateTime from, LocalDateTime to) {
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("checkedAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("checkedAt"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private HealthCheckResponse toResponse(SystemHealthCheck entity) {
        Map<String, ComponentHealthResponse> components;
        try {
            components = objectMapper.readValue(entity.getComponentsJson(), COMPONENTS_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse componentsJson for SystemHealthCheck {}", entity.getId(), e);
            components = Map.of();
        }
        return HealthCheckResponse.builder()
                .overallStatus(entity.getOverallStatus())
                .checkedAt(entity.getCheckedAt())
                .components(components)
                .build();
    }

    private Map<String, ComponentHealthResponse> flatten(HealthComponent root) {
        Map<String, ComponentHealthResponse> result = new LinkedHashMap<>();
        if (root instanceof CompositeHealth composite && composite.getComponents() != null) {
            composite.getComponents().forEach((name, component) -> result.put(name, toComponentResponse(component)));
        } else {
            result.put("system", toComponentResponse(root));
        }
        return result;
    }

    private ComponentHealthResponse toComponentResponse(HealthComponent component) {
        Map<String, Object> details = component instanceof Health health ? health.getDetails() : Map.of();
        return ComponentHealthResponse.builder()
                .status(component.getStatus().getCode())
                .details(details)
                .build();
    }
}
