package com.example.supplychain.assistant;

import com.example.supplychain.data.ShipmentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class AssistantService {
    private static final int MAX_TOOL_CALLS = 5;
    private final ObjectMapper mapper;
    private final ShipmentService shipments;
    @Value("${OPENAI_API_KEY:}") private String apiKey;
    @Value("${OPENAI_MODEL:gpt-4o-mini}") private String model;
    @Value("${ASSISTANT_LOG:./logs/assistant.jsonl}") private String logPath;

    public AssistantService(ObjectMapper mapper, ShipmentService shipments) { this.mapper = mapper; this.shipments = shipments; }

    public String answer(String prompt) throws Exception {
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("Provide --assistant.question with a supply-chain question.");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("Set OPENAI_API_KEY to use the assistant CLI.");
        ArrayNode messages = mapper.createArrayNode();
        messages.addObject().put("role", "system").put("content", "You answer only questions about supply-chain shipments, ports, routes, delay and quality checks. Refuse unrelated requests. Use tools for factual claims and cite the tool name in each factual answer. State clearly that predictions are model estimates, not observed delays.");
        messages.addObject().put("role", "user").put("content", prompt);
        List<Map<String, Object>> allTools = new ArrayList<>();
        for (int requestNumber = 0; requestNumber <= MAX_TOOL_CALLS; requestNumber++) {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.set("messages", messages);
            body.set("tools", tools()); body.put("tool_choice", "auto");
            JsonNode response = callModel(body);
            JsonNode assistantMessage = response.path("choices").path(0).path("message");
            appendLog(Map.of("event", "llm_call", "timestamp", OffsetDateTime.now().toString(), "prompt", prompt,
                    "messages", messages.deepCopy(), "tool_calls_so_far", allTools, "response", assistantMessage));
            messages.add(assistantMessage.deepCopy());
            JsonNode calls = assistantMessage.path("tool_calls");
            if (!calls.isArray() || calls.isEmpty()) {
                String result = assistantMessage.path("content").asText("I could not form an answer from the available data.");
                appendLog(Map.of("event", "llm_final", "timestamp", OffsetDateTime.now().toString(), "prompt", prompt, "tool_calls", allTools, "final_output", result));
                return result;
            }
            if (allTools.size() + calls.size() > MAX_TOOL_CALLS) {
                String result = "I stopped after the five-tool-call limit. Please narrow the question.";
                appendLog(Map.of("event", "llm_final", "timestamp", OffsetDateTime.now().toString(),
                        "prompt", prompt, "tool_calls", allTools, "final_output", result));
                return result;
            }
            for (JsonNode call : calls) {
                String name = call.path("function").path("name").asText();
                JsonNode arguments = mapper.readTree(call.path("function").path("arguments").asText("{}"));
                Object result = dispatch(name, arguments);
                Map<String, Object> record = new LinkedHashMap<>(); record.put("name", name); record.put("arguments", arguments); record.put("response", result);
                allTools.add(record);
                appendLog(Map.of("event", "tool_call", "timestamp", OffsetDateTime.now().toString(), "prompt", prompt, "tool", record));
                ObjectNode toolMessage = messages.addObject(); toolMessage.put("role", "tool"); toolMessage.put("tool_call_id", call.path("id").asText()); toolMessage.put("content", mapper.writeValueAsString(result));
            }
        }
        String result = "I could not complete the question within the tool-call limit.";
        appendLog(Map.of("event", "llm_final", "timestamp", OffsetDateTime.now().toString(),
                "prompt", prompt, "tool_calls", allTools, "final_output", result));
        return result;
    }

    private JsonNode callModel(JsonNode body) throws Exception {
        appendLog(Map.of("event", "llm_request", "timestamp", OffsetDateTime.now().toString(), "request", body.deepCopy()));
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        HttpResponse<String> response;
        try {
            response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception exception) {
            appendLog(Map.of("event", "llm_error", "timestamp", OffsetDateTime.now().toString(),
                    "exception", exception.getClass().getSimpleName(), "message", Objects.toString(exception.getMessage(), "")));
            throw exception;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("LLM request failed with HTTP " + response.statusCode());
        return mapper.readTree(response.body());
    }

    private ArrayNode tools() throws Exception {
        return (ArrayNode) mapper.readTree("""
          [
            {"type":"function","function":{"name":"query_shipments","description":"Search curated shipments by origin, destination, status, and booking date range.","parameters":{"type":"object","properties":{"origin":{"type":"string"},"destination":{"type":"string"},"status":{"type":"string"},"date_from":{"type":"string"},"date_to":{"type":"string"},"page_size":{"type":"integer"}},"additionalProperties":false}}},
            {"type":"function","function":{"name":"get_route_stats","description":"Get average delay, on-time rate and shipment count for a route. Origin and destination may be port codes or full port names.","parameters":{"type":"object","properties":{"origin":{"type":"string"},"destination":{"type":"string"}},"required":["origin","destination"],"additionalProperties":false}}},
            {"type":"function","function":{"name":"predict_delay","description":"Predict booking-time delay risk for an existing shipment ID.","parameters":{"type":"object","properties":{"shipment_id":{"type":"string"}},"required":["shipment_id"],"additionalProperties":false}}}
          ]
          """);
    }

    private Object dispatch(String name, JsonNode args) {
        return switch (name) {
            case "query_shipments" -> shipments.search(text(args, "origin"), text(args, "destination"), text(args, "status"), date(args, "date_from"), date(args, "date_to"), 1, Math.max(1, Math.min(50, args.path("page_size").asInt(10))));
            case "get_route_stats" -> shipments.routeStats(text(args, "origin"), text(args, "destination"));
            case "predict_delay" -> shipments.predict(Map.of("shipment_id", Objects.requireNonNull(text(args, "shipment_id"), "shipment_id is required")));
            default -> Map.of("error", "Tool is not allowlisted");
        };
    }
    private String text(JsonNode node, String name) { return node.hasNonNull(name) ? node.get(name).asText() : null; }
    private LocalDate date(JsonNode node, String name) { return node.hasNonNull(name) ? LocalDate.parse(node.get(name).asText()) : null; }
    private void appendLog(Map<String, Object> entry) throws Exception {
        Path path = Path.of(logPath); Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, mapper.writeValueAsString(entry) + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
