package com.example.supplychain.api;

import com.example.supplychain.data.ShipmentService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.LocalDate;
import java.util.Map;

@RestController
public class ShipmentController {
    private final ShipmentService shipments;

    public ShipmentController(ShipmentService shipments) {
        this.shipments = shipments;
    }

    @GetMapping("/")
    public RedirectView home() {
        return new RedirectView("/swagger-ui/index.html");
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        if (!shipments.isReady()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Serving store is not ready");
        }
        return Map.of("status", "ok", "version", "1.0.0");
    }

    @GetMapping("/shipments/{id}")
    public Map<String, Object> shipment(@PathVariable String id) {
        return shipments.findShipment(id);
    }

    @PostMapping("/shipments")
    public ResponseEntity<Map<String, Object>> createShipment(@RequestBody ShipmentCreateRequest request) {
        Map<String, Object> created = shipments.create(request.toCommand());
        java.net.URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/shipments/{id}").buildAndExpand(created.get("shipment_id")).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/shipments")
    public Map<String, Object> shipments(@RequestParam(required = false) String origin,
                                         @RequestParam(required = false) String destination,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(name = "date_from", required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                                         @RequestParam(name = "date_to", required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(name = "page_size", defaultValue = "50") int pageSize) {
        return shipments.search(origin, destination, status, dateFrom, dateTo, page, pageSize);
    }

    @GetMapping("/routes/{origin}/{destination}/stats")
    public Map<String, Object> routeStats(@PathVariable String origin, @PathVariable String destination) {
        return shipments.routeStats(origin, destination);
    }

    @PostMapping("/predict-delay")
    public Map<String, Object> predict(@RequestBody Map<String, Object> payload) {
        return shipments.predict(payload);
    }

    @GetMapping("/data-quality/report")
    public Map<String, Object> qualityReport() {
        return shipments.qualityReport();
    }
}
