package com.unisage.backend.controller.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.unisage.backend.dto.response.internal.InternalModelRegistryVersionResponse;

import lombok.RequiredArgsConstructor;

/** Internal-only namespace for {@code unisage-agent} — see plan.md "Internal API contract". */
@RestController
@RequestMapping("/internal/model-registry")
@RequiredArgsConstructor
public class InternalModelRegistryController {

    // TODO: wire to ModelRegistryVersionService (Redis + DB version counter) once it exists.
    @GetMapping("/version")
    public InternalModelRegistryVersionResponse version() {
        return new InternalModelRegistryVersionResponse(0L);
    }
}
