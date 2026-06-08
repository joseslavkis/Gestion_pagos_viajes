package com.agencia.pagos.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;

@Service
public class ExchangeRateService {

    private static final Logger logger = LoggerFactory.getLogger(ExchangeRateService.class);

    private static final ZoneId ARGENTINA_ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final DateTimeFormatter HISTORICAL_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private static final String CURRENT_SOURCE = "dolarapi.com";
    private static final String HISTORICAL_SOURCE = "argentinadatos.com";

    private static final int DEFAULT_FALLBACK_DAYS = 10;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int historicalFallbackDays;

    @Autowired
    public ExchangeRateService(
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            @Value("${exchange-rate.fallback-days:10}") int historicalFallbackDays
    ) {
        this(
                restClientBuilder.build(),
                objectMapper,
                Clock.system(ARGENTINA_ZONE),
                historicalFallbackDays > 0 ? historicalFallbackDays : DEFAULT_FALLBACK_DAYS
        );
    }

    ExchangeRateService(RestClient restClient, ObjectMapper objectMapper, Clock clock, int historicalFallbackDays) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.historicalFallbackDays = historicalFallbackDays;
    }

    public ExchangeRateQuote getOfficialQuoteForDate(LocalDate requestedDate) {
        if (requestedDate == null) {
            throw new IllegalArgumentException("La fecha de pago informada es obligatoria");
        }

        LocalDate today = LocalDate.now(clock.withZone(ARGENTINA_ZONE));
        if (requestedDate.isAfter(today)) {
            throw new IllegalArgumentException(
                    "La fecha de pago no puede ser futura (hoy es " + today + ")"
            );
        }

        if (requestedDate.equals(today)) {
            BigDecimal currentRate = fetchCurrentRate(today);
            return new ExchangeRateQuote(currentRate, requestedDate, requestedDate, CURRENT_SOURCE, CURRENT_SOURCE, "");
        }

        return fetchHistoricalWithFallback(requestedDate, today);
    }

    public BigDecimal getOfficialRateForDate(LocalDate date) {
        return getOfficialQuoteForDate(date).sellRate();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal fetchCurrentRate(LocalDate today) {
        String url = "https://dolarapi.com/v1/dolares/oficial";
        long startedAt = System.nanoTime();
        try {
            String response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(response);
            validateCurrentProviderTimestamp(node, today, url);
            BigDecimal rate = extractVenta(node, url, today);
            logger.info("exchange_rate.current.success provider={} requestedDate={} effectiveDate={} url={} elapsedMs={}",
                    CURRENT_SOURCE, today, today, url, elapsedMillis(startedAt));
            return rate;
        } catch (RuntimeException | java.io.IOException e) {
            logger.warn("exchange_rate.current.provider_error provider={} url={} requestedDate={} elapsedMs={} cause={}",
                    CURRENT_SOURCE, url, today, elapsedMillis(startedAt), e.getClass().getSimpleName() + ": " + e.getMessage());
            throw new IllegalStateException(
                    "No se pudo obtener el tipo de cambio oficial del día. Reintentá en unos minutos.",
                    e
            );
        }
    }

    private ExchangeRateQuote fetchHistoricalWithFallback(LocalDate requestedDate, LocalDate today) {
        int maxLookback = Math.max(0, historicalFallbackDays);
        LocalDate probe = normalizeHistoricalCandidate(requestedDate);
        String lastError = null;
        Throwable lastCause = null;

        while (!probe.isBefore(requestedDate.minusDays(maxLookback))) {
            String url = "https://api.argentinadatos.com/v1/cotizaciones/dolares/oficial/"
                    + probe.format(HISTORICAL_DATE_FORMAT);
            long startedAt = System.nanoTime();

            try {
                String response = restClient.get()
                        .uri(url)
                        .retrieve()
                        .body(String.class);
                JsonNode node = objectMapper.readTree(response);
                BigDecimal venta = extractVenta(node, url, probe);
                LocalDate providerDate = extractHistoricalProviderDate(node, url, requestedDate, probe);
                if (providerDate.isAfter(requestedDate)) {
                    throw new IllegalStateException("El proveedor devolvió una fecha posterior a la fecha solicitada: " + providerDate);
                }
                logger.info("exchange_rate.historical.success provider={} requestedDate={} candidateDate={} effectiveDate={} url={} elapsedMs={}",
                        HISTORICAL_SOURCE, requestedDate, probe, providerDate, url, elapsedMillis(startedAt));
                return new ExchangeRateQuote(venta, requestedDate, providerDate, HISTORICAL_SOURCE, HISTORICAL_SOURCE, null);
            } catch (RestClientResponseException notFound) {
                HttpStatusCode status = notFound.getStatusCode();
                if (status.value() == 404) {
                    logger.info("exchange_rate.historical.quote_missing provider={} requestedDate={} candidateDate={} url={} status={} elapsedMs={}",
                            HISTORICAL_SOURCE, requestedDate, probe, url, status.value(), elapsedMillis(startedAt));
                    lastError = "quote_missing";
                    lastCause = notFound;
                    probe = normalizeHistoricalCandidate(probe.minusDays(1));
                    continue;
                }
                logger.warn("exchange_rate.historical.provider_error provider={} url={} status={} requestedDate={} candidateDate={} elapsedMs={} cause={}",
                        HISTORICAL_SOURCE, url, status.value(), requestedDate, probe, elapsedMillis(startedAt), notFound.getClass().getSimpleName() + ": " + notFound.getMessage());
                throw new IllegalStateException(
                        "El proveedor histórico devolvió un error (HTTP " + status.value() + "). Reintentá más tarde.",
                        notFound
                );
            } catch (RuntimeException | java.io.IOException e) {
                logger.warn("exchange_rate.historical.provider_error provider={} url={} requestedDate={} candidateDate={} elapsedMs={} cause={}",
                        HISTORICAL_SOURCE, url, requestedDate, probe, elapsedMillis(startedAt), e.getClass().getSimpleName() + ": " + e.getMessage());
                throw new IllegalStateException(
                        "No se pudo obtener el tipo de cambio histórico. Reintentá en unos minutos.",
                        e
                );
            }
        }

        if ("quote_missing".equals(lastError)) {
            throw new IllegalStateException(
                    "No se encontró una cotización oficial disponible para el "
                            + requestedDate + ". Probá con una fecha más reciente.",
                    lastCause
            );
        }
        throw new IllegalStateException(
                "No se pudo obtener el tipo de cambio para el " + requestedDate
                        + ". Reintentá en unos minutos.",
                lastCause
        );
    }

    private static LocalDate normalizeHistoricalCandidate(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY) {
            return date.minusDays(1);
        }
        if (day == DayOfWeek.SUNDAY) {
            return date.minusDays(2);
        }
        return date;
    }

    private BigDecimal extractVenta(JsonNode node, String url, LocalDate probe) {
        JsonNode ventaNode;
        if (node.isArray()) {
            if (node.isEmpty()) {
                throw new IllegalStateException("El proveedor devolvió una respuesta vacía para " + probe);
            }
            ventaNode = node.get(node.size() - 1).get("venta");
        } else {
            ventaNode = node.get("venta");
        }
        if (ventaNode == null || ventaNode.isNull()) {
            logger.warn("exchange_rate.provider.invalid_venta url={} date={} cause=missing_field", url, probe);
            throw new IllegalStateException("El proveedor no devolvió el campo 'venta' para " + probe);
        }
        BigDecimal venta;
        try {
            if (ventaNode.isNumber()) {
                venta = ventaNode.decimalValue();
            } else if (ventaNode.isTextual()) {
                venta = new BigDecimal(ventaNode.asText());
            } else {
                throw new NumberFormatException("Unsupported JSON type for venta: " + ventaNode.getNodeType());
            }
        } catch (NumberFormatException | ArithmeticException e) {
            logger.warn("exchange_rate.provider.invalid_venta url={} date={} cause=number_format value={}",
                    url, probe, ventaNode.asText());
            throw new IllegalStateException("El valor de 'venta' no es numérico para " + probe, e);
        }
        if (venta == null || venta.signum() <= 0) {
            logger.warn("exchange_rate.provider.invalid_venta url={} date={} cause=non_positive value={}",
                    url, probe, venta);
            throw new IllegalStateException("El valor de 'venta' no es positivo para " + probe);
        }
        return venta;
    }

    private LocalDate extractHistoricalProviderDate(JsonNode node, String url, LocalDate requestedDate, LocalDate expectedDate) {
        JsonNode fechaNode = node.get("fecha");
        if (fechaNode == null || fechaNode.isNull() || !fechaNode.isTextual()) {
            logger.warn("exchange_rate.provider.invalid_fecha url={} requestedDate={} candidateDate={} cause=missing_field",
                    url, requestedDate, expectedDate);
            throw new IllegalStateException("El proveedor no devolvió una fecha válida para " + expectedDate);
        }
        LocalDate providerDate;
        try {
            providerDate = LocalDate.parse(fechaNode.asText());
        } catch (DateTimeParseException e) {
            logger.warn("exchange_rate.provider.invalid_fecha url={} requestedDate={} candidateDate={} value={}",
                    url, requestedDate, expectedDate, fechaNode.asText());
            throw new IllegalStateException("La fecha devuelta por el proveedor no es válida para " + expectedDate, e);
        }
        if (!providerDate.equals(expectedDate)) {
            logger.warn("exchange_rate.provider.invalid_fecha url={} requestedDate={} candidateDate={} providerDate={}",
                    url, requestedDate, expectedDate, providerDate);
            throw new IllegalStateException("La fecha devuelta por el proveedor no coincide con la fecha consultada: " + providerDate);
        }
        return providerDate;
    }

    private void validateCurrentProviderTimestamp(JsonNode node, LocalDate today, String url) {
        JsonNode timestampNode = node.get("fechaActualizacion");
        if (timestampNode == null || timestampNode.isNull() || !timestampNode.isTextual()) {
            return;
        }
        try {
            LocalDate providerDate = Instant.parse(timestampNode.asText()).atZone(ARGENTINA_ZONE).toLocalDate();
            if (providerDate.isAfter(today)) {
                logger.warn("exchange_rate.current.invalid_timestamp url={} today={} providerDate={}", url, today, providerDate);
                throw new IllegalStateException("El proveedor devolvió una fecha futura: " + providerDate);
            }
        } catch (DateTimeParseException e) {
            logger.warn("exchange_rate.current.invalid_timestamp url={} value={}", url, timestampNode.asText());
            throw new IllegalStateException("La fecha de actualización del proveedor no es válida", e);
        }
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }
}
