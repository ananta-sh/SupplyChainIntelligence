package com.example.supplychain;

import com.example.supplychain.ml.DelayModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DelayModelTest {
    @Test void savedModelPredictsWithoutOutcomeFeatures() {
        DelayModelService model = new DelayModelService(new ObjectMapper());
        ReflectionTestUtils.setField(model, "modelPath", "artifacts/delay-model.json");
        model.load();

        Map<String, Object> bookingFeatures = new HashMap<>();
        bookingFeatures.put("origin_port", "DEHAM");
        bookingFeatures.put("destination_port", "NLRTM");
        bookingFeatures.put("cargo_type", "Automotive Parts");
        bookingFeatures.put("container_count", 28);
        bookingFeatures.put("weight_tons", 1056.38);
        bookingFeatures.put("transit_days_planned", 9);

        Map<String, Object> prediction = model.predict(bookingFeatures);
        double probability = ((Number) prediction.get("delay_risk_probability")).doubleValue();
        assertThat(probability).isBetween(0.0, 1.0);
        assertThat(prediction.get("delay_risk")).isIn("low", "high");
        assertThat(DelayModelService.FEATURES).doesNotContain("actual_arrival", "actual_departure", "actual_delay_hours");
    }
}
