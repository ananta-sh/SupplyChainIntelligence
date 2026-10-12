package com.example.supplychain;

import com.example.supplychain.data.DataPipeline;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Starts the service on a random port so these checks exercise the real HTTP stack. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.url=jdbc:sqlite:target/test-serving.db",
                "app.quality-report-path=target/test-data-quality-report.json"})
class ShipmentApiIntegrationTest {
    @Autowired private TestRestTemplate http;
    @Autowired private ObjectMapper mapper;
    @Autowired private DataPipeline pipeline;
    @Autowired private JdbcTemplate jdbc;

    @Test void healthReportsReadyServiceAndVersion() throws Exception {
        ResponseEntity<String> response = http.getForEntity("/health", String.class);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(body.path("status").asText()).isEqualTo("ok");
        assertThat(body.path("version").asText()).isEqualTo("1.0.0");
    }

    @Test void shipmentLookupAndUnknownIdUseMeaningfulResponses() throws Exception {
        ResponseEntity<String> found = http.getForEntity("/shipments/SHP-01801", String.class);
        assertThat(found.getStatusCode().value()).isEqualTo(200);
        assertThat(mapper.readTree(found.getBody()).path("shipment_id").asText()).isEqualTo("SHP-01801");

        ResponseEntity<String> missing = http.getForEntity("/shipments/DOES-NOT-EXIST", String.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(mapper.readTree(missing.getBody()).path("error").path("code").asText()).isNotBlank();
    }

    @Test void shipmentCanBeCreatedAndReadBackOverHttp() throws Exception {
        Map<String, Object> request = Map.ofEntries(
                Map.entry("shipment_id", "API-POST-001"), Map.entry("customer_id", "CUST-API-001"),
                Map.entry("origin_port", "DEHAM"), Map.entry("destination_port", "NLRTM"),
                Map.entry("vessel_id", "VSL-API-001"), Map.entry("booking_date", "2026-10-01 08:00:00"),
                Map.entry("planned_departure", "2026-10-02 08:00:00"),
                Map.entry("planned_arrival", "2026-10-06 08:00:00"), Map.entry("container_count", 12),
                Map.entry("cargo_type", "consumer goods"), Map.entry("weight_tons", 240.5),
                Map.entry("status", "booked"));
        ResponseEntity<String> created = http.postForEntity("/shipments", request, String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getHeaders().getLocation()).isNotNull();
        assertThat(mapper.readTree(created.getBody()).path("route_key").asText()).isEqualTo("DEHAM → NLRTM");

        pipeline.rebuild();

        ResponseEntity<String> fetched = http.getForEntity("/shipments/API-POST-001", String.class);
        JsonNode shipment = mapper.readTree(fetched.getBody());
        assertThat(fetched.getStatusCode().value()).isEqualTo(200);
        assertThat(shipment.path("shipment_id").asText()).isEqualTo("API-POST-001");
        assertThat(shipment.path("cargo_type").asText()).isEqualTo("Consumer Goods");
        assertThat(shipment.path("transit_days_planned").asDouble()).isEqualTo(4.0);
    }

    @Test void qualityReportAndPredictionAreServed() throws Exception {
        ResponseEntity<String> report = http.getForEntity("/data-quality/report", String.class);
        assertThat(report.getStatusCode().value()).isEqualTo(200);
        assertThat(mapper.readTree(report.getBody()).path("checks_run").asInt()).isEqualTo(17);

        ResponseEntity<String> prediction = http.postForEntity("/predict-delay", Map.of(
                "origin_port", "DEHAM", "destination_port", "NLRTM", "cargo_type", "Automotive Parts",
                "container_count", 28, "weight_tons", 1056.38, "transit_days_planned", 9), String.class);
        JsonNode body = mapper.readTree(prediction.getBody());
        assertThat(prediction.getStatusCode().value()).isEqualTo(200);
        assertThat(body.path("delay_risk_probability").isNumber()).isTrue();
        assertThat(body.path("features_used").get(0).asText()).isEqualTo("origin_port");
    }

    @Test void snakeCaseFiltersAndRouteMetricsAreAvailable() throws Exception {
        ResponseEntity<String> response = http.getForEntity("/shipments?origin=DEHAM&status=DELIVERED"
                + "&date_from=2024-01-01&date_to=2025-12-31&page=1&page_size=5", String.class);
        JsonNode page = mapper.readTree(response.getBody());
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(page.path("page_size").asInt()).isEqualTo(5);
        assertThat(page.path("items").isArray()).isTrue();

        ResponseEntity<String> route = http.getForEntity("/routes/DEHAM/NLRTM/stats", String.class);
        assertThat(route.getStatusCode().value()).isEqualTo(200);
        assertThat(mapper.readTree(route.getBody()).path("shipment_count").isNumber()).isTrue();
    }

    @Test void rebuildingServingTablesIsIdempotent() {
        Map<String, Integer> first = pipeline.rebuild();
        Map<String, Integer> second = pipeline.rebuild();
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM curated_shipments", Integer.class))
                .isEqualTo(first.get("curated_shipments"));
    }
}
