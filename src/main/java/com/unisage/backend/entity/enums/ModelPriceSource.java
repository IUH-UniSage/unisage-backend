package com.unisage.backend.entity.enums;

/** {@code MANUAL} rows are SA overrides - the LiteLLM sync never touches them. */
public enum ModelPriceSource {
    LITELLM,
    MANUAL
}
