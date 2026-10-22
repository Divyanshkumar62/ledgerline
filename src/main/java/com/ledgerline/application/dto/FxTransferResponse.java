package com.ledgerline.application.dto;

import com.ledgerline.domain.model.FxConversion;

import java.math.BigDecimal;

/** The four-leg journal entry plus the conversion that produced it. */
public record FxTransferResponse(
        TransferResponse transfer,
        String sourceCurrency,
        String targetCurrency,
        long sourceAmountMinor,
        long targetAmountMinor,
        BigDecimal rate,
        String roundingMode
) {
    public static FxTransferResponse of(TransferResponse transfer, FxConversion fx) {
        return new FxTransferResponse(transfer, fx.sourceCurrency(), fx.targetCurrency(), fx.sourceAmountMinor(),
                fx.targetAmountMinor(), fx.rate().stripTrailingZeros(), fx.roundingMode().name());
    }
}
