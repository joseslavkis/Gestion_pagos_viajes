package com.agencia.pagos.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
public class ExchangeRateService {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ExchangeRateService() {
        this.restClient = RestClient.create();
        this.objectMapper = new ObjectMapper();
    }

    public BigDecimal getOfficialRateForDate(LocalDate date) {
        try {
            LocalDate today = LocalDate.now(ZoneId.of("America/Argentina/Buenos_Aires"));

            String url;
            if (date.equals(today)) {
                url = "https://dolarapi.com/v1/dolares/oficial";
            } else {
                String formatted = date.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
                url = "https://dolarapi.com/v1/historico/dolares/oficial/" + formatted;
            }

            String response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);

            JsonNode node = objectMapper.readTree(response);

            BigDecimal venta;
            if (node.isArray()) {
                if (node.isEmpty()) {
                    throw new IllegalStateException("No exchange-rate rows returned");
                }
                JsonNode last = node.get(node.size() - 1);
                venta = last.get("venta").decimalValue();
            } else {
                venta = node.get("venta").decimalValue();
            }

            return venta;

        } catch (Exception e) {
            throw new IllegalStateException(
                    "No se pudo obtener el tipo de cambio BNA para la fecha "
                            + date + ". Intente con otra fecha.");
        }
    }
}
