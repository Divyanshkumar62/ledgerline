package com.ledgerline.application.service;

import com.ledgerline.domain.exception.InvalidRequestException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Minor-unit conversion (ADR-012). Currencies have different minor units: USD 2 decimals, JPY 0, KWD 3.
 * The rate is quoted in MAJOR units, so: target = source / 10^srcDigits * rate * 10^dstDigits, rounded
 * once, at the end, half-even (no systematic bias when many conversions are summed).
 */
final class FxMath {

    static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    private FxMath() {
    }

    static long convert(long sourceAmountMinor, String sourceCurrency, String targetCurrency, BigDecimal rate) {
        BigDecimal target = BigDecimal.valueOf(sourceAmountMinor)
                .movePointLeft(minorDigits(sourceCurrency))
                .multiply(rate)
                .movePointRight(minorDigits(targetCurrency))
                .setScale(0, ROUNDING);
        if (target.signum() <= 0) {
            throw new InvalidRequestException("Converted amount rounds to zero " + targetCurrency + " minor units");
        }
        try {
            return target.longValueExact();
        } catch (ArithmeticException e) {
            throw new InvalidRequestException("Converted amount is too large");
        }
    }

    static int minorDigits(String currency) {
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        if (digits < 0) {
            throw new InvalidRequestException(currency + " has no minor unit and is not supported");
        }
        return digits;
    }
}
