package com.ledgerline.domain.repository;

import com.ledgerline.domain.model.ApiClient;

import java.util.Optional;

/** Domain port for API clients (ADR-011). Keys are only ever handled as SHA-256 hashes. */
public interface ApiClientRepository {

    Optional<ApiClient> findActiveByKeyHash(String keyHash);

    boolean exists(String clientId);

    void create(ApiClient client, String keyHash);
}
