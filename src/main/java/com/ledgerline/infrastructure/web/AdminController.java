package com.ledgerline.infrastructure.web;

import com.ledgerline.application.dto.AdminActionRequest;
import com.ledgerline.application.dto.AdminActionResult;
import com.ledgerline.application.dto.AuditResult;
import com.ledgerline.application.dto.CreateClientRequest;
import com.ledgerline.application.dto.CreatedClientResponse;
import com.ledgerline.application.service.AccountAdminService;
import com.ledgerline.application.service.ApiClientService;
import com.ledgerline.domain.model.ApiClient;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** ROLE_ADMIN only (SecurityConfig). Every account action requires a reason and is recorded (ADR-013). */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final ApiClientService clients;
    private final AccountAdminService admin;

    public AdminController(ApiClientService clients, AccountAdminService admin) {
        this.clients = clients;
        this.admin = admin;
    }

    /** The API key is in this response and nowhere else, ever. */
    @PostMapping("/clients")
    public ResponseEntity<CreatedClientResponse> createClient(@Valid @RequestBody CreateClientRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(clients.create(request.clientId(), request.displayName(), request.role()));
    }

    @PostMapping("/accounts/{id}/audit")
    public AuditResult audit(@PathVariable UUID id) {
        return admin.audit(id);
    }

    @PostMapping("/accounts/{id}/freeze")
    public AdminActionResult freeze(@AuthenticationPrincipal ApiClient actor, @PathVariable UUID id,
                                    @Valid @RequestBody AdminActionRequest request) {
        return admin.freeze(id, request.reason(), actor.clientId());
    }

    @PostMapping("/accounts/{id}/rebuild-balance")
    public AdminActionResult rebuildBalance(@AuthenticationPrincipal ApiClient actor, @PathVariable UUID id,
                                            @Valid @RequestBody AdminActionRequest request) {
        return admin.rebuildBalance(id, request.reason(), actor.clientId());
    }

    @PostMapping("/accounts/{id}/book-missing-posting")
    public AdminActionResult bookMissingPosting(@AuthenticationPrincipal ApiClient actor, @PathVariable UUID id,
                                                @Valid @RequestBody AdminActionRequest request) {
        return admin.bookMissingPosting(id, request.reason(), actor.clientId());
    }

    @PostMapping("/accounts/{id}/unfreeze")
    public AdminActionResult unfreeze(@AuthenticationPrincipal ApiClient actor, @PathVariable UUID id,
                                      @Valid @RequestBody AdminActionRequest request) {
        return admin.unfreeze(id, request.reason(), actor.clientId());
    }
}
