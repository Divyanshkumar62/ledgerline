package com.ledgerline.domain.model;

import com.ledgerline.domain.exception.CurrencyMismatchException;

import java.io.Serializable;
import java.util.Objects;

/**
 * Value object representing money stored strictly in minor units (e.g. cents, paise)
 * to eliminate floating-point representation and rounding errors.
 */
public record Money(long amountMinor, String currency) implements Serializable, Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "Currency must not be null");
        if (currency.trim().length() != 3) {
            throw new IllegalArgumentException("Currency must be a valid 3-letter ISO code: " + currency);
        }
        currency = currency.trim().toUpperCase();
    }

    public static Money of(long amountMinor, String currency) {
        return new Money(amountMinor, currency);
    }

    public static Money zero(String currency) {
        return new Money(0L, currency);
    }

    public Money add(Money other) {
        assertSameCurrency(other);
        return new Money(Math.addExact(this.amountMinor, other.amountMinor), this.currency);
    }

    public Money subtract(Money other) {
        assertSameCurrency(other);
        return new Money(Math.subtractExact(this.amountMinor, other.amountMinor), this.currency);
    }

    public boolean isPositive() {
        return amountMinor > 0;
    }

    public boolean isNegative() {
        return amountMinor < 0;
    }

    public boolean isZero() {
        return amountMinor == 0;
    }

    private void assertSameCurrency(Money other) {
        Objects.requireNonNull(other, "Other money must not be null");
        if (!this.currency.equalsIgnoreCase(other.currency)) {
            throw new CurrencyMismatchException(
                    "Currency mismatch: expected " + this.currency + " but was " + other.currency);
        }
    }

    @Override
    public int compareTo(Money o) {
        assertSameCurrency(o);
        return Long.compare(this.amountMinor, o.amountMinor);
    }

    @Override
    public String toString() {
        return amountMinor + " " + currency;
    }
}
