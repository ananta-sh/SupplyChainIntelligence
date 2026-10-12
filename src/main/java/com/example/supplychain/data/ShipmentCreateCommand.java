package com.example.supplychain.data;

/** Validatable input values for creating a shipment in the serving store. */
public record ShipmentCreateCommand(
        String shipmentId,
        String customerId,
        String originPort,
        String destinationPort,
        String vesselId,
        String bookingDate,
        String plannedDeparture,
        String actualDeparture,
        String plannedArrival,
        String actualArrival,
        Integer containerCount,
        String cargoType,
        Double weightTons,
        String status) { }
