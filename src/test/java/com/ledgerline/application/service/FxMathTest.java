package com.ledgerline.application.service;

import com.ledgerline.domain.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FxMathTest {

    @Test
    void sameMinorUnits() {
        // 100.00 USD at 0.9150 EUR/USD = 91.50 EUR
        assertThat(FxMath.convert(10_000, "USD", "EUR", new BigDecimal("0.9150"))).isEqualTo(9_150);
    }

    @Test
    void differentMinorUnits() {
        // 12.34 USD (2 decimals) at 151.237 JPY/USD = 1866.26 JPY → JPY has 0 decimals → 1866
        assertThat(FxMath.convert(1_234, "USD", "JPY", new BigDecimal("151.237"))).isEqualTo(1_866);
        // 1000 JPY at 0.0066 USD/JPY = 6.60 USD = 660 cents
        assertThat(FxMath.convert(1_000, "JPY", "USD", new BigDecimal("0.0066"))).isEqualTo(660);
        // 1.00 USD at 0.3074 KWD/USD = 0.3074 KWD → KWD has 3 decimals → 307 fils (0.3074 rounds half-even to 0.307)
        assertThat(FxMath.convert(100, "USD", "KWD", new BigDecimal("0.3074"))).isEqualTo(307);
    }

    @Test
    void roundsHalfEvenOnlyOnce() {
        // 0.01 USD at 0.5 → 0.005 EUR → exactly half a cent → half-even → 0 cents → rejected as zero
        assertThatThrownBy(() -> FxMath.convert(1, "USD", "EUR", new BigDecimal("0.5")))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("rounds to zero");
        // 0.03 USD at 0.5 → 0.015 EUR → half-even → 0.02 EUR (to the even neighbour)
        assertThat(FxMath.convert(3, "USD", "EUR", new BigDecimal("0.5"))).isEqualTo(2);
        // 0.05 USD at 0.5 → 0.025 EUR → half-even → 0.02 EUR (not 0.03)
        assertThat(FxMath.convert(5, "USD", "EUR", new BigDecimal("0.5"))).isEqualTo(2);
    }
}
