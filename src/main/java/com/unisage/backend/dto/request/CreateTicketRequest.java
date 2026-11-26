package com.unisage.backend.dto.request;

import java.util.UUID;

import com.unisage.backend.entity.enums.TicketType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
public record CreateTicketRequest(
    /**
     * The AI answer being reported. Null for a general request from the support page, whose type
     * must then be GENERAL_QUESTION, ACCOUNT_ISSUE or OTHER.
     */
    UUID messageId,

    @NotNull(message = "Loại yêu cầu không được để trống")
    TicketType type,

    @NotBlank(message = "Tiêu đề không được để trống")
    @Size(max = 200, message = "Tiêu đề không được vượt quá 200 ký tự")
    String title,

    @NotBlank(message = "Mô tả không được để trống")
    @Size(max = 2000, message = "Mô tả không được vượt quá 2000 ký tự")
    String description
) {}
