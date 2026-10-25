package com.ledgerline.infrastructure.web;

import com.ledgerline.application.dto.FxTransferOutcome;
import com.ledgerline.application.dto.FxTransferRequest;
import com.ledgerline.application.dto.FxTransferResponse;
import com.ledgerline.application.dto.TransferOutcome;
import com.ledgerline.application.dto.TransferRequest;
import com.ledgerline.application.dto.TransferResponse;
import com.ledgerline.application.service.TransferService;
import com.ledgerline.domain.model.ApiClient;
import com.ledgerline.domain.model.PostingDirection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/v1")
public class TransferController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String CACHE_LOOKUP = "X-Cache-Lookup";
    static final String HIT_IDEMPOTENT = "HIT-IDEMPOTENT";

    private static final Logger log = LoggerFactory.getLogger(TransferController.class);

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * The idempotency key is required: moving money without one would make a network retry a second
     * payment. Keys are scoped to the authenticated client, so two clients can use the same key.
     */
    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> executeTransfer(
            @AuthenticationPrincipal ApiClient client,
            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        correlate(idempotencyKey,
                accountsOn(request.legs().stream().filter(l -> l.direction() == PostingDirection.CREDIT).map(l -> l.accountId())),
                accountsOn(request.legs().stream().filter(l -> l.direction() == PostingDirection.DEBIT).map(l -> l.accountId())));
        TransferOutcome outcome = logged(() -> transferService.executeIdempotent(client.clientId(), idempotencyKey, request));
        return respond(outcome.replayed(), outcome.response().journalEntryId(), outcome.response());
    }

    /** Cross-currency transfer at a client-supplied rate, through FX clearing accounts (ADR-012). */
    @PostMapping("/fx-transfers")
    public ResponseEntity<FxTransferResponse> executeFxTransfer(
            @AuthenticationPrincipal ApiClient client,
            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody FxTransferRequest request) {
        correlate(idempotencyKey, request.fromAccountId().toString(), request.toAccountId().toString());
        FxTransferOutcome outcome = logged(() -> transferService.executeFxIdempotent(client.clientId(), idempotencyKey, request));
        return respond(outcome.replayed(), outcome.response().transfer().journalEntryId(), outcome.response());
    }

    private static void correlate(String idempotencyKey, String sources, String targets) {
        MDC.put(CorrelationFilter.Mdc.IDEMPOTENCY_KEY, idempotencyKey);
        MDC.put(CorrelationFilter.Mdc.SOURCE_ACCOUNT_ID, sources);
        MDC.put(CorrelationFilter.Mdc.TARGET_ACCOUNT_ID, targets);
    }

    private static <T> T logged(Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.info("Transfer not applied: {}", e.getClass().getSimpleName());
            throw e;
        }
    }

    private static <T> ResponseEntity<T> respond(boolean replayed, UUID journalEntryId, T body) {
        log.info("Transfer {} journal_entry_id={}", replayed ? "replayed" : "committed", journalEntryId);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (replayed) {
            response.header(CACHE_LOOKUP, HIT_IDEMPOTENT);
        }
        return response.body(body);
    }

    /** "Source" = accounts on CREDIT legs, "target" = accounts on DEBIT legs (comma-separated for multi-leg). */
    private static String accountsOn(Stream<UUID> ids) {
        return ids.map(String::valueOf).collect(Collectors.joining(","));
    }
}
