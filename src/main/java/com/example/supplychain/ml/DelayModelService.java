package com.example.supplychain.ml;

import com.example.supplychain.data.CsvRows;
import com.example.supplychain.data.DataPipeline;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class DelayModelService {
    public static final List<String> FEATURES = List.of("origin_port", "destination_port", "cargo_type", "container_count", "weight_tons", "transit_days_planned");
    private static final List<String> NUMERIC = List.of("container_count", "weight_tons", "transit_days_planned");
    private static final List<String> CATEGORICAL = List.of("origin_port", "destination_port", "cargo_type");
    private final ObjectMapper mapper;
    @Value("${app.data-dir:./data/raw}") private String dataDir;
    @Value("${app.model-path:./artifacts/delay-model.json}") private String modelPath;
    private JsonNode model;

    public DelayModelService(ObjectMapper mapper) { this.mapper = mapper; }

    public Map<String, Object> train() throws Exception {
        List<Map<String, String>> ports = CsvRows.read(Path.of(dataDir, "ports.csv"));
        Map<String, Long> portCodeCounts = new HashMap<>();
        ports.forEach(row -> portCodeCounts.merge(row.get("port_code"), 1L, Long::sum));
        Set<String> knownPorts = new HashSet<>();
        ports.stream().filter(DataPipeline::validPort)
                .filter(row -> portCodeCounts.getOrDefault(row.get("port_code"), 0L) == 1L)
                .forEach(row -> knownPorts.add(row.get("port_code")));
        List<Map<String, String>> raw = CsvRows.read(Path.of(dataDir, "shipments.csv"));
        Set<String> seen = new HashSet<>(); List<Sample> samples = new ArrayList<>();
        for (Map<String, String> row : raw) {
            if (row.get("shipment_id") == null || !seen.add(row.get("shipment_id"))) continue;
            if (!knownPorts.contains(row.get("origin_port")) || !knownPorts.contains(row.get("destination_port"))) continue;
            if ("CANCELLED".equals(DataPipeline.normalizeStatus(row.get("status")))) continue;
            LocalDateTime booking = DataPipeline.date(row.get("booking_date")), departure = DataPipeline.date(row.get("planned_departure"));
            LocalDateTime arrival = DataPipeline.date(row.get("planned_arrival")), actualArrival = DataPipeline.date(row.get("actual_arrival"));
            double count = DataPipeline.numeric(row.get("container_count")), weight = DataPipeline.numeric(row.get("weight_tons"));
            if (booking == null || departure == null || arrival == null || actualArrival == null || departure.isBefore(booking) || !arrival.isAfter(departure) || count <= 0 || weight <= 0) continue;
            Map<String, Object> feature = new LinkedHashMap<>();
            feature.put("origin_port", row.get("origin_port")); feature.put("destination_port", row.get("destination_port"));
            feature.put("cargo_type", DataPipeline.title(row.get("cargo_type"))); feature.put("container_count", count); feature.put("weight_tons", weight);
            feature.put("transit_days_planned", DataPipeline.hours(departure, arrival) / 24.0);
            int label = DataPipeline.hours(arrival, actualArrival) > 24 ? 1 : 0;
            samples.add(new Sample(feature, label));
        }
        if (samples.size() < 20) throw new IllegalStateException("Not enough labeled rows to train the delay model");
        List<Integer> trainIndices = new ArrayList<>(), testIndices = new ArrayList<>();
        for (int label = 0; label <= 1; label++) {
            List<Integer> group = new ArrayList<>();
            for (int i = 0; i < samples.size(); i++) if (samples.get(i).label == label) group.add(i);
            Collections.shuffle(group, new Random(42 + label)); int split = (int) (group.size() * .75);
            trainIndices.addAll(group.subList(0, split)); testIndices.addAll(group.subList(split, group.size()));
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        List<Double> medians = new ArrayList<>(), means = new ArrayList<>(), scales = new ArrayList<>();
        for (String field : NUMERIC) {
            double[] values = trainIndices.stream().mapToDouble(i -> numeric(samples.get(i).features.get(field))).sorted().toArray();
            double median = values.length % 2 == 0 ? (values[values.length / 2 - 1] + values[values.length / 2]) / 2 : values[values.length / 2];
            double mean = Arrays.stream(values).average().orElse(0), variance = Arrays.stream(values).map(v -> (v - mean) * (v - mean)).average().orElse(1);
            medians.add(median); means.add(mean); scales.add(Math.sqrt(variance) == 0 ? 1 : Math.sqrt(variance));
        }
        Map<String, List<String>> categories = new LinkedHashMap<>();
        for (String field : CATEGORICAL) {
            TreeSet<String> vocab = new TreeSet<>();
            for (int index : trainIndices) vocab.add(Objects.toString(samples.get(index).features.get(field), ""));
            categories.put(field, new ArrayList<>(vocab));
        }
        spec.put("medians", medians); spec.put("means", means); spec.put("scales", scales); spec.put("categories", categories);
        spec.put("features", FEATURES); spec.put("threshold", .5);
        double[][] matrix = new double[samples.size()][]; int[] labels = new int[samples.size()];
        for (int i = 0; i < samples.size(); i++) { matrix[i] = vector(samples.get(i).features, spec); labels[i] = samples.get(i).label; }
        int width = matrix[0].length; double[] coefficients = new double[width];
        double prevalence = trainIndices.stream().mapToInt(i -> labels[i]).average().orElse(.5);
        coefficients[0] = Math.log(prevalence / (1 - prevalence));
        for (int epoch = 0; epoch < 1200; epoch++) {
            double[] gradient = new double[width];
            for (int index : trainIndices) {
                double probability = sigmoid(dot(matrix[index], coefficients));
                double weight = labels[index] == 1 ? .5 / prevalence : .5 / (1 - prevalence);
                for (int j = 0; j < width; j++) gradient[j] += weight * (probability - labels[index]) * matrix[index][j] / trainIndices.size();
            }
            for (int j = 1; j < width; j++) gradient[j] += .03 * coefficients[j];
            for (int j = 0; j < width; j++) coefficients[j] -= .12 * gradient[j];
        }
        spec.put("coefficients", Arrays.stream(coefficients).boxed().toList());
        int tp = 0, fp = 0, fn = 0, correct = 0; List<double[]> scores = new ArrayList<>();
        for (int index : testIndices) {
            double probability = sigmoid(dot(matrix[index], coefficients)); boolean positive = probability >= .5;
            if (positive && labels[index] == 1) tp++; if (positive && labels[index] == 0) fp++; if (!positive && labels[index] == 1) fn++;
            if (positive == (labels[index] == 1)) correct++;
            scores.add(new double[]{probability, labels[index]});
        }
        double precision = ratio(tp, tp + fp), recall = ratio(tp, tp + fn), f1 = ratio(2 * precision * recall, precision + recall);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("model", "L2-regularized weighted logistic regression (Java)");
        metrics.put("target", "actual_arrival more than 24 hours after planned_arrival"); metrics.put("positive_class_rate", round(samples.stream().mapToInt(Sample::label).average().orElse(0)));
        metrics.put("train_rows", trainIndices.size()); metrics.put("test_rows", testIndices.size()); metrics.put("threshold", .5);
        metrics.put("precision", round(precision)); metrics.put("recall", round(recall)); metrics.put("f1", round(f1));
        metrics.put("auc", round(auc(scores))); metrics.put("accuracy", round((double) correct / testIndices.size()));
        metrics.put("features", FEATURES); metrics.put("split", "Random stratified 75/25, fixed seed 42; temporal validation is recommended for production.");
        Path path = Path.of(modelPath); Files.createDirectories(path.toAbsolutePath().getParent()); mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), spec);
        Path metricsPath = path.resolveSibling("model-metrics.json"); mapper.writerWithDefaultPrettyPrinter().writeValue(metricsPath.toFile(), metrics);
        this.model = mapper.valueToTree(spec);
        return metrics;
    }

    public void load() {
        try { this.model = mapper.readTree(Path.of(modelPath).toFile()); }
        catch (Exception exception) { throw new IllegalStateException("Delay model artifact is missing or unreadable: " + modelPath, exception); }
    }

    public Map<String, Object> predict(Map<String, ?> features) {
        if (model == null) load();
        double probability = sigmoid(dot(vector(features, model), doubles(model.path("coefficients")).stream().mapToDouble(Double::doubleValue).toArray()));
        return Map.of("delay_risk_probability", round(probability), "delay_risk", probability >= .5 ? "high" : "low", "threshold", .5);
    }

    private double[] vector(Map<String, ?> row, Map<String, Object> spec) {
        List<Double> means = castDoubles(spec.get("means")), scales = castDoubles(spec.get("scales")), medians = castDoubles(spec.get("medians"));
        List<Double> values = new ArrayList<>(); values.add(1.0);
        for (int i = 0; i < NUMERIC.size(); i++) { double value = numeric(row.get(NUMERIC.get(i))); if (!Double.isFinite(value)) value = medians.get(i); values.add((value - means.get(i)) / scales.get(i)); }
        @SuppressWarnings("unchecked") Map<String, List<String>> categories = (Map<String, List<String>>) spec.get("categories");
        for (String field : CATEGORICAL) { String value = Objects.toString(row.get(field), ""); for (String category : categories.get(field)) values.add(category.equals(value) ? 1.0 : 0.0); }
        return values.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private double[] vector(Map<String, ?> row, JsonNode spec) {
        List<Double> means = doubles(spec.path("means")), scales = doubles(spec.path("scales")), medians = doubles(spec.path("medians"));
        List<Double> values = new ArrayList<>(); values.add(1.0);
        for (int i = 0; i < NUMERIC.size(); i++) { double value = numeric(row.get(NUMERIC.get(i))); if (!Double.isFinite(value)) value = medians.get(i); values.add((value - means.get(i)) / scales.get(i)); }
        for (String field : CATEGORICAL) { String value = Objects.toString(row.get(field), ""); for (JsonNode category : spec.path("categories").path(field)) values.add(category.asText().equals(value) ? 1.0 : 0.0); }
        return values.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private List<Double> doubles(JsonNode node) { List<Double> out = new ArrayList<>(); node.forEach(value -> out.add(value.asDouble())); return out; }
    private List<Double> castDoubles(Object value) { return ((List<?>) value).stream().map(v -> ((Number) v).doubleValue()).toList(); }
    private double numeric(Object value) { try { return value == null ? Double.NaN : Double.parseDouble(value.toString()); } catch (Exception ignored) { return Double.NaN; } }
    private double dot(double[] a, double[] b) { double total = 0; for (int i = 0; i < a.length; i++) total += a[i] * b[i]; return total; }
    private double sigmoid(double value) { value = Math.max(-30, Math.min(30, value)); return 1 / (1 + Math.exp(-value)); }
    private double ratio(double numerator, double denominator) { return denominator == 0 ? 0 : numerator / denominator; }
    private double round(double value) { return Math.round(value * 10_000.0) / 10_000.0; }
    private double auc(List<double[]> scores) {
        long wins = 0, ties = 0, pairs = 0;
        for (double[] pos : scores) if (pos[1] == 1) for (double[] neg : scores) if (neg[1] == 0) {
            pairs++; if (pos[0] > neg[0]) wins++; else if (pos[0] == neg[0]) ties++;
        }
        return pairs == 0 ? 0 : (wins + .5 * ties) / pairs;
    }
    private record Sample(Map<String, Object> features, int label) { }
}
