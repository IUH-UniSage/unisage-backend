package com.unisage.backend.event;

/** Fired after a registry-changing transaction commits — payload is just the new version. */
public record ModelRegistryChangedEvent(long version) {
}
