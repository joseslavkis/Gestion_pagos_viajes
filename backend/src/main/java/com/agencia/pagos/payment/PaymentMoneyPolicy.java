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
        if (tripCurrency == paymentCurrency || balance.signum() == 0) {
            return balance;
        }

        BigDecimal rate = requireProviderRate(exchangeRate);
        BigInteger rateUnscaled = rate.unscaledValue();
        int rateScale = rate.scale();
        if (rateScale < 0) {
            rateUnscaled = rateUnscaled.multiply(BigInteger.TEN.pow(-rateScale));
            rateScale = 0;
        }

        BigInteger tripCents = balance.movePointRight(MONEY_SCALE).toBigIntegerExact();
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
        return new BigDecimal(maxPaymentCents, MONEY_SCALE);
    }
}
