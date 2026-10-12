package com.example.supplychain;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

@SpringBootApplication
@OpenAPIDefinition(info = @Info(
        title = "Supply Chain Intelligence API",
        version = "1.0.0",
        description = "Shipment search, route performance, delay risk prediction, and data quality reporting."
))
public class SupplyChainApplication {
    public static void main(String[] args) {
        SpringApplication.run(SupplyChainApplication.class, args);
    }
}
