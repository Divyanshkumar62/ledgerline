package com.ledgerline.application.dto;

/** {@code apiKey} is returned exactly once. Only its SHA-256 is stored, so it cannot be shown again. */
public record CreatedClientResponse(String clientId, String role, String apiKey) {
}
