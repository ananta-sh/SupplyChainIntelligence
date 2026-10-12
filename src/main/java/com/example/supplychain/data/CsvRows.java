package com.example.supplychain.data;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CsvRows {
    private CsvRows() { }

    public static List<Map<String, String>> read(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)) {
            List<Map<String, String>> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                Map<String, String> row = new LinkedHashMap<>();
                for (String header : parser.getHeaderNames()) row.put(header, clean(record.get(header)));
                rows.add(row);
            }
            return rows;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read CSV: " + file, exception);
        }
    }

    public static String clean(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
