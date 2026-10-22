package com.ledgerline.application.dto;

import com.ledgerline.domain.model.ApiClient;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateClientRequest(
        @NotBlank
        @Pattern(regexp = "[a-z0-9][a-z0-9-]{2,63}", message = "clientId: 3-64 chars, lowercase letters, digits and '-'")
        String clientId,

        @NotBlank
        @Size(max = 128)
        String displayName,

        @NotNull(message = "Role is required (CLIENT or ADMIN)")
        ApiClient.Role role
) {}
