package com.example.supplychain.data;

import com.example.supplychain.ml.DelayModelService;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Coordinates shipment queries and domain-level prediction rules for API and assistant callers. */
@Service
public class ShipmentService {
    private final ShipmentRepository shipments;
    private final RawQualityService quality;
    private final DelayModelService model;

    public ShipmentService(ShipmentRepository shipments, RawQualityService quality, DelayModelService model) {
        this.shipments = shipments;
        this.quality = quality;
        this.model = model;
    }

    public boolean isReady() {
        return shipments.isReady();
    }

    public Map<String, Object> findShipment(String shipmentId) {
        return shipments.findById(shipmentId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Shipment not found: " + shipmentId));
    }

    public Map<String, Object> create(ShipmentCreateCommand command) {
        List<String> invalid = new ArrayList<>();
        String shipmentId = trimToNull(command.shipmentId());
        String originInput = trimToNull(command.originPort());
        String destinationInput = trimToNull(command.destinationPort());
        require(shipmentId, "shipment_id", invalid);
        require(originInput, "origin_port", invalid);
        require(destinationInput, "destination_port", invalid);
        require(command.bookingDate(), "booking_date", invalid);
        require(command.plannedDeparture(), "planned_departure", invalid);
        require(command.plannedArrival(), "planned_arrival", invalid);
        require(command.cargoType(), "cargo_type", invalid);
        require(command.status(), "status", invalid);
        if (command.containerCount() == null || command.containerCount() <= 0) invalid.add("container_count must be positive");
        if (command.weightTons() == null || !Double.isFinite(command.weightTons()) || command.weightTons() <= 0) {
            invalid.add("weight_tons must be positive and finite");
        }
        String origin = shipments.resolvePortCode(originInput);
        String destination = shipments.resolvePortCode(destinationInput);
        if (originInput != null && !shipments.portExists(origin)) invalid.add("origin_port is not a known port");
        if (destinationInput != null && !shipments.portExists(destination)) invalid.add("destination_port is not a known port");

        LocalDateTime booking = parse(command.bookingDate(), "booking_date", invalid);
        LocalDateTime plannedDeparture = parse(command.plannedDeparture(), "planned_departure", invalid);
        LocalDateTime plannedArrival = parse(command.plannedArrival(), "planned_arrival", invalid);
        LocalDateTime actualDeparture = parseOptional(command.actualDeparture(), "actual_departure", invalid);
        LocalDateTime actualArrival = parseOptional(command.actualArrival(), "actual_arrival", invalid);
        if (booking != null && plannedDeparture != null && plannedDeparture.isBefore(booking)) {
            invalid.add("planned_departure must be on or after booking_date");
        }
        if (plannedDeparture != null && plannedArrival != null && !plannedArrival.isAfter(plannedDeparture)) {
            invalid.add("planned_arrival must be after planned_departure");
        }
        if (actualDeparture != null && actualArrival != null && actualArrival.isBefore(actualDeparture)) {
            invalid.add("actual_arrival must be on or after actual_departure");
        }
        if (!invalid.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join("; ", invalid));
        }
        if (shipments.findById(shipmentId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Shipment already exists: " + shipmentId);
        }

        Double delay = actualArrival == null ? null : DataPipeline.hours(plannedArrival, actualArrival);
        Double transitActual = actualDeparture == null || actualArrival == null
                ? null : DataPipeline.hours(actualDeparture, actualArrival) / 24.0;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("shipment_id", shipmentId);
        row.put("customer_id", command.customerId());
        row.put("origin_port", origin);
        row.put("destination_port", destination);
        row.put("vessel_id", command.vesselId());
        row.put("booking_date", DataPipeline.text(booking));
        row.put("planned_departure", DataPipeline.text(plannedDeparture));
        row.put("actual_departure", DataPipeline.text(actualDeparture));
        row.put("planned_arrival", DataPipeline.text(plannedArrival));
        row.put("actual_arrival", DataPipeline.text(actualArrival));
        row.put("container_count", command.containerCount());
        row.put("cargo_type", DataPipeline.title(command.cargoType()));
        row.put("weight_tons", command.weightTons());
        row.put("status", DataPipeline.normalizeStatus(command.status()));
        row.put("actual_delay_hours", delay);
        row.put("on_time_flag", delay == null ? null : delay <= 24.0 ? 1 : 0);
        row.put("route_key", origin + " → " + destination);
        row.put("transit_days_planned", DataPipeline.hours(plannedDeparture, plannedArrival) / 24.0);
        row.put("transit_days_actual", transitActual);
        try {
            shipments.insert(row);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Shipment already exists: " + shipmentId);
        }
        return findShipment(shipmentId);
    }

    private String trimToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private void require(String value, String field, List<String> invalid) {
        if (value == null || value.isBlank()) invalid.add(field + " is required");
    }

    private LocalDateTime parse(String value, String field, List<String> invalid) {
        LocalDateTime parsed = DataPipeline.date(value);
        if (parsed == null) invalid.add(field + " must use YYYY-MM-DD HH:mm:ss format");
        return parsed;
    }

    private LocalDateTime parseOptional(String value, String field, List<String> invalid) {
        return value == null || value.isBlank() ? null : parse(value, field, invalid);
    }

    public Map<String, Object> search(String origin, String destination, String status,
                                      LocalDate dateFrom, LocalDate dateTo, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "page must be >= 1 and pageSize must be between 1 and 200");
        }
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dateFrom must be on or before dateTo");
        }
        return shipments.search(origin, destination, status,
                dateFrom == null ? null : dateFrom.toString(), dateTo == null ? null : dateTo.toString(), page, pageSize);
    }

    public Map<String, Object> routeStats(String origin, String destination) {
        return shipments.routeStats(origin, destination);
    }

    public Map<String, Object> predict(Map<String, ?> payload) {
        String shipmentId = Objects.toString(payload.get("shipment_id"), null);
        Map<String, ?> features = shipmentId == null ? payload : findShipment(shipmentId);
        List<String> missing = DelayModelService.FEATURES.stream()
                .filter(feature -> features.get(feature) == null).toList();
        if (!missing.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Missing booking-time features: " + String.join(", ", missing));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shipment_id", shipmentId);
        result.putAll(model.predict(features));
        result.put("features_used", DelayModelService.FEATURES);
        return result;
    }

    public Map<String, Object> qualityReport() {
        return quality.run();
    }
}
