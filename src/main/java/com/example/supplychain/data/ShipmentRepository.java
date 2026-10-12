package com.example.supplychain.data;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Owns SQL access to the curated shipment and port tables. */
@Repository
public class ShipmentRepository {
    private final JdbcTemplate jdbc;

    public ShipmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isReady() {
        try {
            jdbc.queryForObject("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'curated_shipments'", Integer.class);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    public Optional<Map<String, Object>> findById(String shipmentId) {
        return jdbc.queryForList("SELECT * FROM api_created_shipments WHERE shipment_id = ? "
                        + "UNION ALL SELECT * FROM curated_shipments WHERE shipment_id = ? LIMIT 1", shipmentId, shipmentId)
                .stream().findFirst();
    }

    public boolean portExists(String portCode) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ports WHERE port_code = ?", Integer.class, portCode);
        return count != null && count > 0;
    }

    public void insert(Map<String, Object> shipment) {
        jdbc.update("INSERT INTO api_created_shipments VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                shipment.get("shipment_id"), shipment.get("customer_id"), shipment.get("origin_port"),
                shipment.get("destination_port"), shipment.get("vessel_id"), shipment.get("booking_date"),
                shipment.get("planned_departure"), shipment.get("actual_departure"), shipment.get("planned_arrival"),
                shipment.get("actual_arrival"), shipment.get("container_count"), shipment.get("cargo_type"),
                shipment.get("weight_tons"), shipment.get("status"), shipment.get("actual_delay_hours"),
                shipment.get("on_time_flag"), shipment.get("route_key"), shipment.get("transit_days_planned"),
                shipment.get("transit_days_actual"));
    }

    public Map<String, Object> search(String origin, String destination, String status,
                                      String dateFrom, String dateTo, int page, int pageSize) {
        List<String> predicates = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        add(predicates, args, "origin_port = ?", resolvePortCode(origin));
        add(predicates, args, "destination_port = ?", resolvePortCode(destination));
        add(predicates, args, "status = ?", status == null ? null : status.toUpperCase(Locale.ROOT));
        add(predicates, args, "booking_date >= ?", dateFrom == null ? null : dateFrom + " 00:00:00");
        add(predicates, args, "booking_date <= ?", dateTo == null ? null : dateTo + " 23:59:59");
        String where = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
        String source = "(SELECT * FROM curated_shipments UNION ALL SELECT * FROM api_created_shipments)";
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM " + source + where,
                Integer.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((long) (page - 1) * pageSize);
        List<Map<String, Object>> items = jdbc.queryForList("SELECT * FROM " + source + where
                + " ORDER BY booking_date DESC LIMIT ? OFFSET ?", pageArgs.toArray());
        return Map.of("items", items, "page", page, "page_size", pageSize, "total", total == null ? 0 : total);
    }

    public Map<String, Object> routeStats(String origin, String destination) {
        String resolvedOrigin = resolvePortCode(origin);
        String resolvedDestination = resolvePortCode(destination);
        Map<String, Object> row = jdbc.queryForMap("SELECT COUNT(actual_delay_hours) AS shipment_count, "
                        + "AVG(actual_delay_hours) AS average_delay_hours, AVG(on_time_flag * 1.0) AS on_time_rate "
                        + "FROM (SELECT * FROM curated_shipments UNION ALL SELECT * FROM api_created_shipments) "
                        + "WHERE origin_port = ? AND destination_port = ?",
                resolvedOrigin, resolvedDestination);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("origin", resolvedOrigin);
        result.put("destination", resolvedDestination);
        result.put("shipment_count", row.get("shipment_count"));
        result.put("average_delay_hours", row.get("average_delay_hours"));
        result.put("on_time_rate", row.get("on_time_rate"));
        return result;
    }

    public String resolvePortCode(String codeOrName) {
        if (codeOrName == null) return null;
        List<String> matches = jdbc.query("SELECT port_code FROM ports WHERE UPPER(port_code) = UPPER(?) "
                        + "OR LOWER(TRIM(port_name)) = LOWER(TRIM(?)) LIMIT 1",
                (rs, rowNum) -> rs.getString("port_code"), codeOrName, codeOrName);
        return matches.isEmpty() ? codeOrName.toUpperCase(Locale.ROOT) : matches.get(0);
    }

    private void add(List<String> predicates, List<Object> args, String predicate, Object value) {
        if (value != null) {
            predicates.add(predicate);
            args.add(value);
        }
    }
}
