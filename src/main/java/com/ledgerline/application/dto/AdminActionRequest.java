package com.ledgerline.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Every admin intervention must say why; the reason is stored permanently in the audit trail. */
public record AdminActionRequest(
        @NotBlank(message = "A reason is required")
        @Size(max = 500, message = "Reason must be at most 500 characters")
        String reason
) {}
