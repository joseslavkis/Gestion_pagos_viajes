package com.agencia.pagos.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpStatus.NOT_FOUND;

class ExchangeRateServiceTest {

    private MockRestServiceServer server;
    private ExchangeRateService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LocalDate today = LocalDate.of(2026, 6, 8);
    private final ZoneId argentinaZone = ZoneId.of("America/Argentina/Buenos_Aires");
    private final Clock fixedClock = Clock.fixed(
            today.atTime(15, 0).atZone(argentinaZone).toInstant(),
            ZoneOffset.UTC
    );

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new ExchangeRateService(builder.build(), objectMapper, fixedClock, 10);
    }

    @Test
    void usesCurrentProviderWhenDateIsToday() {
        server.expect(requestTo("https://dolarapi.com/v1/dolares/oficial"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("{\"venta\":1234.56,\"fechaActualizacion\":\"2026-06-08T11:20:00.000Z\"}", MediaType.APPLICATION_JSON));

        ExchangeRateQuote quote = service.getOfficialQuoteForDate(today);

        assertMoneyEquals("1234.56", quote.sellRate());
        assertEquals(today, quote.requestedDate());
        assertEquals(today, quote.effectiveDate());
        assertEquals("dolarapi.com", quote.source());
    }

    @Test
    void rejectsFutureDatesBeforeProviderCall() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.getOfficialQuoteForDate(today.plusDays(1))
        );
        assertTrue(ex.getMessage().toLowerCase().contains("futura"));
    }

    @Test
    void historicalHappyPathReturnsExactDateWhenQuoteExists() {
        LocalDate requested = today.minusDays(3);
        server.expect(requestTo("https://api.argentinadatos.com/v1/cotizaciones/dolares/oficial/"
                + requested.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))))
                .andRespond(withSuccess("{\"venta\":1100.00,\"fecha\":\"2026-06-05\"}", MediaType.APPLICATION_JSON));

        ExchangeRateQuote quote = service.getOfficialQuoteForDate(requested);

        assertMoneyEquals("1100.00", quote.sellRate());
        assertEquals(requested, quote.requestedDate());
        assertEquals(requested, quote.effectiveDate());
        assertEquals("argentinadatos.com", quote.source());
    }

    @Test
    void historicalFallsBackWhenQuoteMissingAndEffectiveDateDiffers() {
        LocalDate requested = LocalDate.of(2026, 6, 4);
        String urlRequested = "https://api.argentinadatos.com/v1/cotizaciones/dolares/oficial/"
                + requested.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        LocalDate previous = requested.minusDays(1);
        String urlPrevious = "https://api.argentinadatos.com/v1/cotizaciones/dolares/oficial/"
                + previous.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"));

        server.expect(requestTo(urlRequested)).andRespond(withStatus(NOT_FOUND));
        server.expect(requestTo(urlPrevious))
                .andRespond(withSuccess("{\"venta\":1080.00,\"fecha\":\"2026-06-03\"}", MediaType.APPLICATION_JSON));

        ExchangeRateQuote quote = service.getOfficialQuoteForDate(requested);

        assertMoneyEquals("1080.00", quote.sellRate());
        assertEquals(requested, quote.requestedDate());
        assertEquals(previous, quote.effectiveDate());
    }

    @Test
    void saturdayFallsBackToFridayDirectly() {
        LocalDate saturday = LocalDate.of(2026, 6, 6);
        LocalDate friday = saturday.minusDays(1);

        server.expect(requestTo("https://api.argentinadatos.com/v1/cotizaciones/dolares/oficial/"
                + friday.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))))
                .andRespond(withSuccess("{\"venta\":1090.00,\"fecha\":\"2026-06-05\"}", MediaType.APPLICATION_JSON));

        ExchangeRateQuote quote = service.getOfficialQuoteForDate(saturday);

        assertMoneyEquals("1090.00", quote.sellRate());
        assertEquals(saturday, quote.requestedDate());
        assertEquals(friday, quote.effectiveDate());
    }

    @Test
    void sundayFallsBackToFridaySkippingSaturdayAttempt() {
        LocalDate sunday = LocalDate.of(2026, 6, 7);
        LocalDate friday = sunday.minusDays(2);

        server.expect(requestTo("https://api.argentinadatos.com/v1/cotizaciones/dolares/oficial/"
                + friday.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))))
                .andRespond(withSuccess("{\"venta\":1095.00,\"fecha\":\"2026-06-05\"}", MediaType.APPLICATION_JSON));

        ExchangeRateQuote quote = service.getOfficialQuoteForDate(sunday);

        assertMoneyEquals("1095.00", quote.sellRate());
        assertEquals(friday, quote.effectiveDate());
    }

    @Test
    void serverErrorDoesNotFallBackButThrowsWithCause() {
        LocalDate requested = today.minusDays(3);
        server.expect(requestTo("https://api.argentinadatos.com/v1/cotizaciones/dolares/oficial/"
                + requested.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))))
                .andRespond(withServerError());

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.getOfficialQuoteForDate(requested)
        );
        assertNotNull(ex.getCause());
    }

    @Test
    void invalidVentaWithNonNumericValueThrows() {
        LocalDate requested = today.minusDays(3);
        server.expect(requestTo("https://api.argentinadatos.com/v1/cotizaciones/dolares/oficial/"
                + requested.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))))
                .andRespond(withSuccess("{\"venta\":\"no-es-numero\",\"fecha\":\"2026-06-05\"}", MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class, () -> service.getOfficialQuoteForDate(requested));
    }

    @Test
    void zeroVentaRejected() {
        LocalDate requested = today.minusDays(3);
        server.expect(requestTo("https://api.argentinadatos.com/v1/cotizaciones/dolares/oficial/"
                + requested.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))))
                .andRespond(withSuccess("{\"venta\":0,\"fecha\":\"2026-06-05\"}", MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class, () -> service.getOfficialQuoteForDate(requested));
    }

    @Test
    void negativeVentaRejected() {
        LocalDate requested = today.minusDays(3);
        server.expect(requestTo("https://api.argentinadatos.com/v1/cotizaciones/dolares/oficial/"
                + requested.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))))
                .andRespond(withSuccess("{\"venta\":-1,\"fecha\":\"2026-06-05\"}", MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class, () -> service.getOfficialQuoteForDate(requested));
    }

    @Test
    void missingProviderDateRejected() {
        LocalDate requested = today.minusDays(3);
        server.expect(requestTo("https://api.argentinadatos.com/v1/cotizaciones/dolares/oficial/"
                + requested.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))))
                .andRespond(withSuccess("{\"venta\":1100.00}", MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class, () -> service.getOfficialQuoteForDate(requested));
    }

    @Test
    void invalidProviderDateRejected() {
        LocalDate requested = today.minusDays(3);
        server.expect(requestTo("https://api.argentinadatos.com/v1/cotizaciones/dolares/oficial/"
                + requested.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))))
                .andRespond(withSuccess("{\"venta\":1100.00,\"fecha\":\"not-a-date\"}", MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class, () -> service.getOfficialQuoteForDate(requested));
    }

    @Test
    void providerDateLaterThanRequestedRejected() {
        LocalDate requested = today.minusDays(3);
        server.expect(requestTo("https://api.argentinadatos.com/v1/cotizaciones/dolares/oficial/"
                + requested.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))))
                .andRespond(withSuccess("{\"venta\":1100.00,\"fecha\":\"2026-06-08\"}", MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class, () -> service.getOfficialQuoteForDate(requested));
    }

    @Test
    void noQuoteInFallbackWindowThrows() {
        LocalDate requested = today.minusDays(2);
        LocalDate probe = requested.minusDays(1);
        for (int i = 0; i < 10; i++) {
            server.expect(requestTo("https://api.argentinadatos.com/v1/cotizaciones/dolares/oficial/"
                    + probe.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))))
                    .andRespond(withStatus(NOT_FOUND));
            probe = previousExpectedCandidate(probe);
        }

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.getOfficialQuoteForDate(requested)
        );
        assertTrue(ex.getMessage().toLowerCase().contains("cotización"));
    }

    private static LocalDate previousExpectedCandidate(LocalDate date) {
        LocalDate candidate = date.minusDays(1);
        if (candidate.getDayOfWeek() == java.time.DayOfWeek.SATURDAY) {
            return candidate.minusDays(1);
        }
        if (candidate.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            return candidate.minusDays(2);
        }
        return candidate;
    }

    private static void assertMoneyEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
