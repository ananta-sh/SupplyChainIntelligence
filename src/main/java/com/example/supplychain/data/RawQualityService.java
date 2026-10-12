package com.example.supplychain.data;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Predicate;

@Service
public class RawQualityService {
    @Value("${app.data-dir:./data/raw}") private String dataDir;

    public Map<String, Object> run() {
        Path root = Path.of(dataDir);
        List<Map<String, String>> ports = CsvRows.read(root.resolve("ports.csv"));
        List<Map<String, String>> shipments = CsvRows.read(root.resolve("shipments.csv"));
        List<Map<String, String>> events = CsvRows.read(root.resolve("port_events.csv"));
        Map<String, Long> portCodeCounts = new HashMap<>();
        ports.forEach(row -> portCodeCounts.merge(row.get("port_code"), 1L, Long::sum));
        Set<String> portCodes = new HashSet<>();
        ports.stream().filter(DataPipeline::validPort)
                .filter(row -> portCodeCounts.getOrDefault(row.get("port_code"), 0L) == 1L)
                .forEach(row -> portCodes.add(row.get("port_code")));
        List<Map<String, Object>> checks = new ArrayList<>();
        add(checks, "shipment_duplicate_id", "shipments", shipments, duplicate(shipments, "shipment_id"), "critical", "Keep first row by shipment_id and flag conflicts.", "Repeated shipment_id values.");
        add(checks, "event_duplicate_id", "port_events", events, duplicate(events, "event_id"), "warning", "Keep first event_id during curation.", "Repeated event_id values.");
        add(checks, "exact_duplicate_shipment_row", "shipments", shipments, repeatedRows(shipments), "warning", "Drop exact duplicate rows during curation.", "Every column equals a prior row.");
        add(checks, "unknown_origin_port", "shipments", shipments, row -> !portCodes.contains(row.get("origin_port")), "critical", "Quarantine shipments with an unknown origin.", "origin_port is absent from ports.csv.");
        add(checks, "unknown_destination_port", "shipments", shipments, row -> !portCodes.contains(row.get("destination_port")), "critical", "Quarantine shipments with an unknown destination.", "destination_port is absent from ports.csv.");
        add(checks, "unknown_event_port", "port_events", events, row -> !portCodes.contains(row.get("port_code")), "critical", "Quarantine events whose port_code is absent from ports.csv.", "port_events.port_code is absent from ports.csv.");
        add(checks, "missing_actual_timestamps", "shipments", shipments, row -> blank(row.get("actual_arrival")) || blank(row.get("actual_departure")), "info", "Preserve in-flight rows; omit missing outcomes from metrics and model labels.", "Actual departure or arrival is blank.");
        add(checks, "missing_shipment_attributes", "shipments", shipments, row -> blank(row.get("cargo_type")) || blank(row.get("weight_tons")) || blank(row.get("status")), "warning", "Keep fields nullable and flag; do not fabricate values.", "Cargo, weight, or status is blank.");
        Set<String> statuses = Set.of("DELIVERED", "DELAYED", "CANCELLED", "COMPLETED", "COMPLETE");
        add(checks, "noncanonical_status", "shipments", shipments, row -> !statuses.contains(orEmpty(row.get("status")).trim().toUpperCase(Locale.ROOT)), "warning", "Normalize known case and aliases; preserve nulls for review.", "Status is blank or outside canonical vocabulary.");
        add(checks, "noncanonical_cargo_type", "shipments", shipments, row -> !blank(row.get("cargo_type")) && (!row.get("cargo_type").equals(row.get("cargo_type").trim()) || !row.get("cargo_type").equals(title(row.get("cargo_type")))), "info", "Trim and title-case cargo labels during curation.", "Cargo label has whitespace or noncanonical casing.");
        add(checks, "invalid_weight_or_container_count", "shipments", shipments, row -> number(row.get("weight_tons")) <= 0 || number(row.get("container_count")) <= 0, "critical", "Quarantine nonpositive quantity values.", "Weight or container count is not positive.");
        add(checks, "invalid_planned_timeline", "shipments", shipments, row -> invalidPlanned(row), "critical", "Quarantine unparsable or impossible planned dates.", "Booking, departure, or arrival cannot be parsed or ordered.");
        add(checks, "invalid_actual_timeline", "shipments", shipments, this::invalidActual, "critical", "Quarantine impossible actual timelines.", "Actual arrival precedes actual departure.");
        add(checks, "missing_event_type_or_notes", "port_events", events, row -> blank(row.get("event_type")) || blank(row.get("notes")), "info", "Retain events; leave missing descriptive fields null.", "Event type or notes is blank.");
        add(checks, "duplicate_port_code", "ports", ports, duplicate(ports, "port_code"), "critical", "Quarantine duplicate port codes before building the reference table.", "Repeated port_code values.");
        add(checks, "missing_port_attributes", "ports", ports, row -> blank(row.get("port_code")) || blank(row.get("port_name")) || blank(row.get("country")) || blank(row.get("region")) || blank(row.get("timezone")) || blank(row.get("avg_congestion_score")), "warning", "Quarantine incomplete rows from the dimension and correct source metadata.", "One or more port reference attributes are blank.");
        add(checks, "invalid_port_reference", "ports", ports, row -> blank(row.get("port_code")) || number(row.get("avg_congestion_score")) < 0 || number(row.get("avg_congestion_score")) > 1, "critical", "Quarantine invalid reference rows; congestion must be between 0 and 1.", "Missing code or congestion outside [0, 1].");
        return Map.of("report_version", "1.0", "source", "raw CSV files", "datasets", Map.of("ports", ports.size(), "shipments", shipments.size(), "port_events", events.size()),
                "checks_run", checks.size(), "checks_with_findings", checks.stream().filter(check -> ((Number) check.get("rows_affected")).intValue() > 0).count(),
                "checks", checks, "policy", "Percentages use rows in the named raw dataset. Checks overlap and are not additive.");
    }

    private void add(List<Map<String, Object>> out, String name, String dataset, List<Map<String, String>> rows,
                     Predicate<Map<String, String>> predicate, String severity, String action, String detection) {
        int affected = (int) rows.stream().filter(predicate).count();
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("check_name", name); check.put("dataset", dataset); check.put("rows_affected", affected);
        check.put("total_rows", rows.size()); check.put("percent_of_total", rows.isEmpty() ? 0 : Math.round(100_000.0 * affected / rows.size()) / 1000.0);
        check.put("severity", severity); check.put("recommended_action", action); check.put("detection", detection);
        out.add(check);
    }

    private Predicate<Map<String, String>> duplicate(List<Map<String, String>> rows, String field) {
        Map<String, Long> counts = new HashMap<>();
        rows.forEach(row -> counts.merge(row.get(field), 1L, Long::sum));
        return row -> counts.getOrDefault(row.get(field), 0L) > 1;
    }
    private Predicate<Map<String, String>> repeatedRows(List<Map<String, String>> rows) {
        Map<Map<String, String>, Long> counts = rows.stream().collect(java.util.stream.Collectors.groupingBy(row -> row, java.util.stream.Collectors.counting()));
        return row -> counts.getOrDefault(row, 0L) > 1;
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String orEmpty(String value) { return value == null ? "" : value; }
    private double number(String value) { try { return value == null ? 0 : Double.parseDouble(value.trim()); } catch (Exception ignored) { return 0; } }
    private LocalDateTime date(String value) { try { return value == null ? null : LocalDateTime.parse(value.replace(' ', 'T')); } catch (Exception ignored) { return null; } }
    private boolean invalidPlanned(Map<String, String> row) {
        LocalDateTime booking = date(row.get("booking_date")), depart = date(row.get("planned_departure")), arrive = date(row.get("planned_arrival"));
        return booking == null || depart == null || arrive == null || depart.isBefore(booking) || !arrive.isAfter(depart);
    }
    private boolean invalidActual(Map<String, String> row) {
        LocalDateTime depart = date(row.get("actual_departure")), arrive = date(row.get("actual_arrival"));
        return depart != null && arrive != null && arrive.isBefore(depart);
    }
    private String title(String text) {
        StringBuilder result = new StringBuilder(); boolean cap = true;
        for (char ch : text.trim().toLowerCase(Locale.ROOT).toCharArray()) { result.append(cap ? Character.toUpperCase(ch) : ch); cap = Character.isWhitespace(ch); }
        return result.toString();
    }
}
