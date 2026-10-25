package com.ledgerline.infrastructure.security;

import com.ledgerline.application.service.ApiClientService;
import com.ledgerline.domain.model.ApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Solves the first-key problem: creating clients needs an admin key, and the first admin key has to come
 * from somewhere. If {@code ledgerline.security.bootstrap-admin-key} is set and no "admin" client exists,
 * one is created with that key. Use a secret from your environment, never a committed value, in production.
 */
@Component
class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);
    static final String ADMIN_CLIENT_ID = "admin";

    private final ApiClientService clients;
    private final String bootstrapKey;

    BootstrapAdmin(ApiClientService clients, @Value("${ledgerline.security.bootstrap-admin-key:}") String bootstrapKey) {
        this.clients = clients;
        this.bootstrapKey = bootstrapKey;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (bootstrapKey.isBlank() || clients.exists(ADMIN_CLIENT_ID)) {
            return;
        }
        clients.createWithKey(ADMIN_CLIENT_ID, "Bootstrap admin", ApiClient.Role.ADMIN, bootstrapKey);
        log.warn("Created bootstrap admin client '{}' from configuration. Rotate this key outside local development.", ADMIN_CLIENT_ID);
    }
}
