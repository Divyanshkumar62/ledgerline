package com.ledgerline.application.service;

import com.ledgerline.application.dto.CreatedClientResponse;
import com.ledgerline.domain.exception.InvalidRequestException;
import com.ledgerline.domain.model.ApiClient;
import com.ledgerline.domain.repository.ApiClientRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** API clients and their keys (ADR-011). */
@Service
public class ApiClientService {

    private static final int MIN_CONFIGURED_KEY_LENGTH = 24;

    private final ApiClientRepository repository;

    public ApiClientService(ApiClientRepository repository) {
        this.repository = repository;
    }

    public Optional<ApiClient> authenticate(String presentedKey) {
        return repository.findActiveByKeyHash(ApiKeys.hash(presentedKey));
    }

    public CreatedClientResponse create(String clientId, String displayName, ApiClient.Role role) {
        String key = ApiKeys.generate();
        createWithKey(clientId, displayName, role, key);
        return new CreatedClientResponse(clientId, role.name(), key);
    }

    /** For a key supplied by configuration (the bootstrap admin), not generated here. */
    public void createWithKey(String clientId, String displayName, ApiClient.Role role, String key) {
        if (role == ApiClient.Role.SYSTEM) {
            throw new InvalidRequestException("The SYSTEM role cannot be assigned");
        }
        if (key == null || key.length() < MIN_CONFIGURED_KEY_LENGTH) {
            throw new InvalidRequestException("An API key must be at least " + MIN_CONFIGURED_KEY_LENGTH + " characters");
        }
        if (repository.exists(clientId)) {
            throw new InvalidRequestException("Client '" + clientId + "' already exists");
        }
        repository.create(new ApiClient(clientId, displayName, role, true), ApiKeys.hash(key));
    }

    public boolean exists(String clientId) {
        return repository.exists(clientId);
    }
}
