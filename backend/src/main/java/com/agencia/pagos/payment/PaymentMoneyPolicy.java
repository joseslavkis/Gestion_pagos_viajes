package com.agencia.pagos.payment;

import com.agencia.pagos.shared.money.Currency;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

@Component
public class PaymentMoneyPolicy {

    public static final int MONEY_SCALE = 2;
    public static final int RATE_SCALE_LIMIT = 8;
    public static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    /**
     * Largest amount persistible in decimal(10,2) money columns (99,999,999.99).
     * Single source of truth for the domain-wide monetary ceiling.
     */
    public static final BigDecimal MAX_MONEY = new BigDecimal("99999999.99");

    private static final BigDecimal MIN_RATE = new BigDecimal("0.00000001");
    private static final BigDecimal MAX_RATE = new BigDecimal("9999999999.99999999");

    public BigDecimal requireMoney(BigDecimal value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        try {
            return value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(fieldName + " must not contain fractions smaller than one cent", exception);
        }
    }

    public BigDecimal requirePositiveMoney(BigDecimal value, String fieldName) {
        BigDecimal money = requireMoney(value, fieldName);
        if (money.signum() <= 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than zero");
        }
        return money;
    }

    /**
     * Validates a money amount that will be persisted into a decimal(10,2) column.
     * Rejects values above {@link #MAX_MONEY} with a business error instead of
     * letting them fail as a numeric overflow in PostgreSQL.
     */
    public BigDecimal requirePersistableMoney(BigDecimal value, String fieldName) {
        BigDecimal money = requireMoney(value, fieldName);
        if (money.compareTo(MAX_MONEY) > 0) {
            throw new IllegalArgumentException(fieldName + " must not exceed 99999999.99");
        }
        return money;
    }

    /**
     * Validates a single-operation input amount: strictly positive and persistible
     * into a decimal(10,2) aggregate. Does not constrain sums or intermediate
     * balances, which may legitimately exceed {@link #MAX_MONEY}.
     */
    public BigDecimal requirePositivePersistableMoney(BigDecimal value, String fieldName) {
        BigDecimal money = requirePersistableMoney(value, fieldName);
        if (money.signum() <= 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than zero");
        }
        return money;
    }

    public BigDecimal requireProviderRate(BigDecimal rate) {
        if (rate == null) {
            throw new ProviderRateContractException(
                    "El proveedor devolvió un tipo de cambio fuera del contrato soportado"
            );
        }
        BigDecimal normalizedRate = rate.scale() < 0
                ? rate.setScale(0, RoundingMode.UNNECESSARY)
                : rate;
        if (normalizedRate.scale() > RATE_SCALE_LIMIT
                || normalizedRate.compareTo(MIN_RATE) < 0
                || normalizedRate.compareTo(MAX_RATE) > 0) {
            throw new ProviderRateContractException(
                    "El proveedor devolvió un tipo de cambio fuera del contrato soportado"
            );
        }
        return normalizedRate;
    }

    public BigDecimal roundMoney(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("Money value is required");
        }
        return value.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }

    public BigDecimal convertPaymentToTripCurrency(
            BigDecimal reportedAmount,
            Currency tripCurrency,
            Currency paymentCurrency,
            BigDecimal exchangeRate
    ) {
        BigDecimal amount = requireMoney(reportedAmount, "reportedAmount");
        if (tripCurrency == paymentCurrency) {
            return amount;
        }

        BigDecimal rate = requireProviderRate(exchangeRate);
        if (tripCurrency == Currency.USD && paymentCurrency == Currency.ARS) {
            return amount.divide(rate, MONEY_SCALE, MONEY_ROUNDING);
        }
        if (tripCurrency == Currency.ARS && paymentCurrency == Currency.USD) {
            return roundMoney(amount.multiply(rate));
        }
        throw new IllegalStateException("Conversión de moneda no soportada");
    }

    public BigDecimal convertTripToPaymentCurrency(
            BigDecimal amountInTripCurrency,
            Currency tripCurrency,
            Currency paymentCurrency,
            BigDecimal exchangeRate
    ) {
        BigDecimal amount = requireMoney(amountInTripCurrency, "amountInTripCurrency");
        if (tripCurrency == paymentCurrency) {
            return amount;
        }

        BigDecimal rate = requireProviderRate(exchangeRate);
        if (tripCurrency == Currency.USD && paymentCurrency == Currency.ARS) {
            return roundMoney(amount.multiply(rate));
        }
        if (tripCurrency == Currency.ARS && paymentCurrency == Currency.USD) {
            return amount.divide(rate, MONEY_SCALE, MONEY_ROUNDING);
        }
        throw new IllegalStateException("Conversión de moneda no soportada");
    }

    /**
     * Largest payment amount coverable by a SINGLE operation without overflowing
     * any NUMERIC(10,2) aggregate. The trip-side balance is capped at
     * {@link #MAX_MONEY} before the exact cents math runs (a lone operation can
     * never persist more than that in trip currency), and the payment-side
     * result is capped too (a lone operation can never persist more than that
     * in payment currency). The cents/half-cent rounding construction is
     * preserved untouched; only the inputs and the final result are bounded.
     */
    public BigDecimal maxAllowedPaymentAmount(
            BigDecimal totalBalanceInTripCurrency,
            Currency tripCurrency,
            Currency paymentCurrency,
            BigDecimal exchangeRate
    ) {
        BigDecimal balance = requireMoney(totalBalanceInTripCurrency, "totalBalanceInTripCurrency");
        if (balance.signum() < 0) {
            throw new IllegalArgumentException("totalBalanceInTripCurrency must not be negative");
        }
        BigDecimal persistibleBalance = balance.compareTo(MAX_MONEY) > 0 ? MAX_MONEY : balance;
        if (tripCurrency == paymentCurrency || balance.signum() == 0) {
            return persistibleBalance;
        }

        BigDecimal rate = requireProviderRate(exchangeRate);
        BigInteger rateUnscaled = rate.unscaledValue();
        int rateScale = rate.scale();
        if (rateScale < 0) {
            rateUnscaled = rateUnscaled.multiply(BigInteger.TEN.pow(-rateScale));
            rateScale = 0;
        }

        BigInteger tripCents = persistibleBalance.movePointRight(MONEY_SCALE).toBigIntegerExact();
        BigInteger strictTripHalfCentBoundary = tripCents.multiply(BigInteger.TWO).add(BigInteger.ONE);
        BigInteger rateScaleFactor = BigInteger.TEN.pow(rateScale);
        BigInteger numerator;
        BigInteger denominator;
        if (tripCurrency == Currency.ARS && paymentCurrency == Currency.USD) {
            numerator = strictTripHalfCentBoundary.multiply(rateScaleFactor);
            denominator = rateUnscaled.multiply(BigInteger.TWO);
        } else if (tripCurrency == Currency.USD && paymentCurrency == Currency.ARS) {
            numerator = strictTripHalfCentBoundary.multiply(rateUnscaled);
            denominator = rateScaleFactor.multiply(BigInteger.TWO);
        } else {
            throw new IllegalStateException("Conversión de moneda no soportada");
        }

        BigInteger maxPaymentCents = numerator.subtract(BigInteger.ONE).divide(denominator);
        BigDecimal maxPaymentAmount = new BigDecimal(maxPaymentCents, MONEY_SCALE);
        return maxPaymentAmount.compareTo(MAX_MONEY) > 0 ? MAX_MONEY : maxPaymentAmount;
    }
}
