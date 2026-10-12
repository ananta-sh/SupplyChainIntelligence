package com.example.supplychain;

import com.example.supplychain.data.DataPipeline;
import com.example.supplychain.data.RawQualityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DataQualityTest {
    @TempDir Path temp;

    @Test void rawChecksRunWithoutAnIngestionStore() throws Exception {
        Files.writeString(temp.resolve("ports.csv"), "port_code,port_name,country,region,timezone,avg_congestion_score\nCNSHA,Shanghai,China,APAC,UTC+8,0.8\nNLRTM,Rotterdam,Netherlands,EUR,UTC+1,0.4\n");
        String header = "shipment_id,customer_id,origin_port,destination_port,vessel_id,booking_date,planned_departure,actual_departure,planned_arrival,actual_arrival,container_count,cargo_type,weight_tons,status\n";
        String row = "SHP-1,C1,CNSHA,NLRTM,V1,2024-06-01 00:00:00,2024-06-02 00:00:00,2024-06-02 00:00:00,2024-06-12 00:00:00,2024-06-12 12:00:00,2,Food & Beverage,50,DELIVERED\n";
        Files.writeString(temp.resolve("shipments.csv"), header + row + row);
        Files.writeString(temp.resolve("port_events.csv"), "event_id,event_timestamp,port_code,vessel_id,event_type,delay_minutes,notes\nE1,2024-06-01 00:00:00,CNSHA,V1,ARRIVAL,0,clear\n");
        RawQualityService service = new RawQualityService(); ReflectionTestUtils.setField(service, "dataDir", temp.toString());
        Map<String, Object> report = service.run();
        List<Map<String, Object>> checks = (List<Map<String, Object>>) report.get("checks");
        Map<String, Object> duplicate = checks.stream().filter(rowCheck -> rowCheck.get("check_name").equals("shipment_duplicate_id")).findFirst().orElseThrow();
        assertThat(report.get("checks_run")).isEqualTo(17);
        assertThat(duplicate.get("rows_affected")).isEqualTo(2);
    }

    @Test void delayAndStatusHelpersHaveExpectedSemantics() {
        assertThat(DataPipeline.hours(LocalDateTime.parse("2024-06-01T00:00:00"), LocalDateTime.parse("2024-06-02T12:00:00"))).isEqualTo(36.0);
        assertThat(DataPipeline.normalizeStatus(" Complete ")).isEqualTo("DELIVERED");
        assertThat(DataPipeline.title(" food & beverage ")).isEqualTo("Food & Beverage");
    }
}
