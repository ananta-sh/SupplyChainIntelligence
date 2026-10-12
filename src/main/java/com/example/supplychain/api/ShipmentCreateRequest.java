package com.example.supplychain.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.example.supplychain.data.ShipmentCreateCommand;

/** Request body for adding a shipment to the local serving store. */
public record ShipmentCreateRequest(
        @JsonProperty("shipment_id") String shipmentId,
        @JsonProperty("customer_id") String customerId,
        @JsonProperty("origin_port") String originPort,
        @JsonProperty("destination_port") String destinationPort,
        @JsonProperty("vessel_id") String vesselId,
        @JsonProperty("booking_date") String bookingDate,
        @JsonProperty("planned_departure") String plannedDeparture,
        @JsonProperty("actual_departure") String actualDeparture,
        @JsonProperty("planned_arrival") String plannedArrival,
        @JsonProperty("actual_arrival") String actualArrival,
        @JsonProperty("container_count") Integer containerCount,
        @JsonProperty("cargo_type") String cargoType,
        @JsonProperty("weight_tons") Double weightTons,
        String status) {
    public ShipmentCreateCommand toCommand() {
        return new ShipmentCreateCommand(shipmentId, customerId, originPort, destinationPort, vesselId,
                bookingDate, plannedDeparture, actualDeparture, plannedArrival, actualArrival,
                containerCount, cargoType, weightTons, status);
    }
}
