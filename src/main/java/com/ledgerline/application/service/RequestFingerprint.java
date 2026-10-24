package com.ledgerline.application.service;

import com.ledgerline.application.dto.FxTransferRequest;
import com.ledgerline.application.dto.TransferLegRequest;
import com.ledgerline.application.dto.TransferRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * SHA-256 of a canonical form of the request, built from parsed fields rather than raw JSON
 * bytes: whitespace, field order and currency case in the JSON do not change the fingerprint,
 * while any change to what is moved (accounts, directions, amounts, legs order) does.
 */
final class RequestFingerprint {

    private RequestFingerprint() {
    }

    static String of(FxTransferRequest request) {
        return sha256("FX\n" + request.description() + '\n' + request.fromAccountId() + '\n' + request.toAccountId()
                + '\n' + request.sourceAmountMinor() + '\n' + request.rate().stripTrailingZeros().toPlainString());
    }

    static String of(TransferRequest request) {
        StringBuilder canonical = new StringBuilder()
                .append(request.currency().trim().toUpperCase(Locale.ROOT)).append('\n')
                .append(request.description()).append('\n');
        for (TransferLegRequest leg : request.legs()) {
            canonical.append(leg.accountId()).append(':').append(leg.direction()).append(':').append(leg.amountMinor()).append('\n');
        }
        return sha256(canonical.toString());
    }

    private static String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
