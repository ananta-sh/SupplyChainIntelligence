package com.example.supplychain.data;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class DataPipeline {
    private final JdbcTemplate jdbc;
    @Value("${app.data-dir:./data/raw}") private String dataDir;
    public DataPipeline(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Map<String, Integer> rebuild() {
        Path root = Path.of(dataDir);
        List<Map<String, String>> ports = CsvRows.read(root.resolve("ports.csv"));
        List<Map<String, String>> shipments = CsvRows.read(root.resolve("shipments.csv"));
        List<Map<String, String>> events = CsvRows.read(root.resolve("port_events.csv"));
        Set<String> knownPorts = new HashSet<>();
        Map<String, Long> portCodeCounts = new HashMap<>();
        ports.forEach(row -> portCodeCounts.merge(row.get("port_code"), 1L, Long::sum));
        ports.stream().filter(DataPipeline::validPort)
                .filter(row -> portCodeCounts.getOrDefault(row.get("port_code"), 0L) == 1L)
                .forEach(row -> knownPorts.add(row.get("port_code")));
        jdbc.execute("DROP TABLE IF EXISTS curated_port_events");
        jdbc.execute("DROP TABLE IF EXISTS curated_shipments");
        jdbc.execute("DROP TABLE IF EXISTS ports");
        jdbc.execute("CREATE TABLE ports (port_code TEXT PRIMARY KEY, port_name TEXT, country TEXT, region TEXT, timezone TEXT, avg_congestion_score REAL)");
        jdbc.execute("CREATE TABLE curated_shipments (shipment_id TEXT PRIMARY KEY, customer_id TEXT, origin_port TEXT, destination_port TEXT, vessel_id TEXT, booking_date TEXT, planned_departure TEXT, actual_departure TEXT, planned_arrival TEXT, actual_arrival TEXT, container_count INTEGER, cargo_type TEXT, weight_tons REAL, status TEXT, actual_delay_hours REAL, on_time_flag INTEGER, route_key TEXT, transit_days_planned REAL, transit_days_actual REAL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS api_created_shipments (shipment_id TEXT PRIMARY KEY, customer_id TEXT, origin_port TEXT, destination_port TEXT, vessel_id TEXT, booking_date TEXT, planned_departure TEXT, actual_departure TEXT, planned_arrival TEXT, actual_arrival TEXT, container_count INTEGER, cargo_type TEXT, weight_tons REAL, status TEXT, actual_delay_hours REAL, on_time_flag INTEGER, route_key TEXT, transit_days_planned REAL, transit_days_actual REAL)");
        jdbc.execute("CREATE TABLE curated_port_events (event_id TEXT PRIMARY KEY, event_timestamp TEXT, port_code TEXT, vessel_id TEXT, event_type TEXT, delay_minutes INTEGER, notes TEXT)");
        ports.stream().filter(DataPipeline::validPort)
                .filter(row -> portCodeCounts.getOrDefault(row.get("port_code"), 0L) == 1L)
                .forEach(row -> jdbc.update("INSERT OR IGNORE INTO ports VALUES (?,?,?,?,?,?)", row.get("port_code"), row.get("port_name"), row.get("country"), row.get("region"), row.get("timezone"), numeric(row.get("avg_congestion_score"))));
        Set<String> seenShipments = new HashSet<>();
        int shipmentCount = 0;
        for (Map<String, String> row : shipments) {
            if (row.get("shipment_id") == null || !seenShipments.add(row.get("shipment_id"))) continue;
            if (!knownPorts.contains(row.get("origin_port")) || !knownPorts.contains(row.get("destination_port"))) continue;
            if (numeric(row.get("container_count")) <= 0 || numeric(row.get("weight_tons")) <= 0) continue;
            LocalDateTime booking = date(row.get("booking_date")), plannedDep = date(row.get("planned_departure")), plannedArr = date(row.get("planned_arrival"));
            if (booking == null || plannedDep == null || plannedArr == null || plannedDep.isBefore(booking) || !plannedArr.isAfter(plannedDep)) continue;
            LocalDateTime actualDep = date(row.get("actual_departure")), actualArr = date(row.get("actual_arrival"));
            if (actualDep != null && actualArr != null && actualArr.isBefore(actualDep)) continue;
            double transitPlanned = hours(plannedDep, plannedArr) / 24.0;
            Double transitActual = actualDep == null || actualArr == null ? null : hours(actualDep, actualArr) / 24.0;
            Double delay = actualArr == null ? null : hours(plannedArr, actualArr);
            String cargo = title(row.get("cargo_type"));
            String status = normalizeStatus(row.get("status"));
            jdbc.update("INSERT INTO curated_shipments VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    row.get("shipment_id"), row.get("customer_id"), row.get("origin_port"), row.get("destination_port"), row.get("vessel_id"),
                    text(booking), text(plannedDep), text(actualDep), text(plannedArr), text(actualArr), (int) numeric(row.get("container_count")), cargo,
                    numeric(row.get("weight_tons")), status, delay, delay == null ? null : (delay <= 24 ? 1 : 0),
                    row.get("origin_port") + " → " + row.get("destination_port"), transitPlanned, transitActual);
            shipmentCount++;
        }
        Set<String> seenEvents = new HashSet<>(); int eventCount = 0;
        for (Map<String, String> row : events) {
            if (row.get("event_id") == null || !seenEvents.add(row.get("event_id")) || !knownPorts.contains(row.get("port_code"))) continue;
            jdbc.update("INSERT OR IGNORE INTO curated_port_events VALUES (?,?,?,?,?,?,?)", row.get("event_id"), row.get("event_timestamp"), row.get("port_code"), row.get("vessel_id"), row.get("event_type"), numeric(row.get("delay_minutes")), row.get("notes"));
            eventCount++;
        }
        jdbc.execute("CREATE INDEX idx_shipments_route ON curated_shipments(origin_port,destination_port)");
        jdbc.execute("CREATE INDEX idx_shipments_booking ON curated_shipments(booking_date)");
        return Map.of("ports", ports.size(), "curated_shipments", shipmentCount, "curated_port_events", eventCount);
    }

    public static LocalDateTime date(String value) {
        try { return value == null ? null : LocalDateTime.parse(value.replace(' ', 'T')); }
        catch (DateTimeParseException exception) { return null; }
    }
    public static double hours(LocalDateTime from, LocalDateTime to) { return Duration.between(from, to).toSeconds() / 3600.0; }
    public static double numeric(String value) { try { return value == null ? 0 : Double.parseDouble(value.trim()); } catch (Exception ignored) { return 0; } }
    public static boolean validPort(Map<String, String> row) {
        if (row.get("port_code") == null || row.get("port_code").isBlank()
                || row.get("port_name") == null || row.get("port_name").isBlank()
                || row.get("country") == null || row.get("country").isBlank()
                || row.get("region") == null || row.get("region").isBlank()
                || row.get("timezone") == null || row.get("timezone").isBlank()) return false;
        try {
            double congestion = Double.parseDouble(row.get("avg_congestion_score"));
            return Double.isFinite(congestion) && congestion >= 0 && congestion <= 1;
        } catch (Exception ignored) {
            return false;
        }
    }
    public static String text(LocalDateTime value) { return value == null ? null : value.toString().replace('T', ' '); }
    public static String normalizeStatus(String value) {
        if (value == null) return null;
        return switch (value.trim().toUpperCase(Locale.ROOT)) { case "COMPLETE", "COMPLETED" -> "DELIVERED"; default -> value.trim().toUpperCase(Locale.ROOT); };
    }
    public static String title(String value) {
        if (value == null) return null;
        String[] words = value.trim().toLowerCase(Locale.ROOT).split("\\s+");
        StringJoiner out = new StringJoiner(" ");
        for (String word : words) out.add(word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1));
        return out.toString();
    }
}
