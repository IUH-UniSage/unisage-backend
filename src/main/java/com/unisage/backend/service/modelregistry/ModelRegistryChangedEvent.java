package com.unisage.backend.service.modelregistry;

/** Fired after a registry-changing transaction commits — payload is just the new version. */
public record ModelRegistryChangedEvent(long version) {
}
