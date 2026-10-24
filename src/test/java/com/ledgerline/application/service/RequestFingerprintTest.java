package com.ledgerline.application.service;

import com.ledgerline.application.dto.TransferLegRequest;
import com.ledgerline.application.dto.TransferRequest;
import com.ledgerline.domain.model.PostingDirection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static TransferRequest request(String currency, long amount) {
        return new TransferRequest("rent", currency, List.of(
                new TransferLegRequest(A, PostingDirection.CREDIT, amount),
                new TransferLegRequest(B, PostingDirection.DEBIT, amount)));
    }

    @Test
    void sameMeaning_sameFingerprint() {
        assertThat(RequestFingerprint.of(request("usd", 100))).isEqualTo(RequestFingerprint.of(request(" USD ", 100)));
    }

    @Test
    void anyChangeToWhatMoves_changesFingerprint() {
        assertThat(RequestFingerprint.of(request("USD", 100))).isNotEqualTo(RequestFingerprint.of(request("USD", 101)));
        assertThat(RequestFingerprint.of(request("USD", 100))).hasSize(64);
    }
}
