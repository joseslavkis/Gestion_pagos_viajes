package com.agencia.pagos.payment;

import com.agencia.pagos.shared.money.Currency;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentMoneyPolicyTest {

    private final PaymentMoneyPolicy policy = new PaymentMoneyPolicy();

    @Test
    void moneyAcceptsEquivalentTrailingZerosAndNormalizesToScaleTwo() {
        assertEquals(new BigDecimal("10.00"), policy.requireMoney(new BigDecimal("10.0"), "amount"));
        assertEquals(new BigDecimal("10.00"), policy.requireMoney(new BigDecimal("10.000"), "amount"));
    }

    @Test
    void moneyRejectsNonZeroPrecisionBeyondCents() {
        assertThrows(
                IllegalArgumentException.class,
                () -> policy.requireMoney(new BigDecimal("10.001"), "amount")
        );
    }

    @Test
    void ratePreservesAcceptedLexicalScale() {
        BigDecimal rate = policy.requireProviderRate(new BigDecimal("1015.50"));

        assertEquals(new BigDecimal("1015.50"), rate);
        assertEquals(2, rate.scale());
    }

    @Test
    void rateRejectsScaleAndRangeOutsideProviderContract() {
        assertThrows(
                ProviderRateContractException.class,
                () -> policy.requireProviderRate(new BigDecimal("1.123456789"))
        );
        assertThrows(
                ProviderRateContractException.class,
                () -> policy.requireProviderRate(new BigDecimal("10000000000.00000000"))
        );
    }

    @Test
    void conversionUsesTheAuthoritativeRateWithoutPrematureRounding() {
        assertEquals(
                new BigDecimal("1234567.00"),
                policy.convertPaymentToTripCurrency(
                        new BigDecimal("1000.00"), Currency.ARS, Currency.USD, new BigDecimal("1234.567"))
        );
        assertEquals(
                new BigDecimal("1234564.00"),
                policy.convertPaymentToTripCurrency(
                        new BigDecimal("1000.00"), Currency.ARS, Currency.USD, new BigDecimal("1234.564"))
        );
        assertEquals(
                new BigDecimal("10.16"),
                policy.convertPaymentToTripCurrency(
                        new BigDecimal("0.01"), Currency.ARS, Currency.USD, new BigDecimal("1015.50"))
        );
    }

    @Test
    void safeLimitIsTheLargestCentWhoseRoundedConversionFitsTheBalance() {
        assertSafeLimit("100.00", Currency.ARS, Currency.USD, "101.30", "0.98");
        assertSafeLimit("200.00", Currency.ARS, Currency.USD, "3", "66.66");
        assertSafeLimit("0.01", Currency.USD, Currency.ARS, "1015.50", "15.23");
    }

    private void assertSafeLimit(
            String balance,
            Currency tripCurrency,
            Currency paymentCurrency,
            String rate,
            String expectedLimit
    ) {
        BigDecimal tripBalance = new BigDecimal(balance);
        BigDecimal exchangeRate = new BigDecimal(rate);
        BigDecimal safeLimit = policy.maxAllowedPaymentAmount(
                tripBalance, tripCurrency, paymentCurrency, exchangeRate);

        assertEquals(new BigDecimal(expectedLimit), safeLimit);
        assertTrue(policy.convertPaymentToTripCurrency(
                        safeLimit, tripCurrency, paymentCurrency, exchangeRate)
                .compareTo(tripBalance) <= 0);
        assertTrue(policy.convertPaymentToTripCurrency(
                        safeLimit.add(new BigDecimal("0.01")), tripCurrency, paymentCurrency, exchangeRate)
                .compareTo(tripBalance) > 0);
    }
}
