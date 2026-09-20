package dev.quantti.fplassistant.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;

/** Thread-safe, per-subscription trace state. It deliberately copies no provider metadata map. */
final class ChatTraceCollector {

    private static final int MAX_ERROR_CHARS = 1000;
    private static final List<String> SUMMARY_KEYS = List.of("reasoningSummary", "reasoning_summary");
    // Disjoint alternatives cannot consume an unescaped closing quote; possessive repetition
    // avoids per-character recursive backtracking (and stack overflow) on long prompts.
    private static final Pattern QUOTED_PAIR = Pattern.compile(
            "\"((?:\\\\.|[^\"\\\\])*+)\"(\\s*:\\s*)\"(?:\\\\.|[^\"\\\\])*+\"");

    private final ObjectMapper json = new ObjectMapper();
    private final Instant acceptedAt = Instant.now();
    private final List<Round> rounds = new ArrayList<>();
    private final List<ToolEvent> tools = new ArrayList<>();
    private final AtomicLong eventSequence = new AtomicLong();
    private String status = "accepted";
    private String finalAnswer;
    private String partialAnswer;
    private ErrorEvent error;

    synchronized RoundHandle startRound(ChatClientRequest request) {
        Round round = new Round(rounds.size() + 1, eventSequence.incrementAndGet(),
                Instant.now(), System.nanoTime(), messages(request));
        rounds.add(round);
        return new RoundHandle(round);
    }

    synchronized void capture(RoundHandle handle, ChatClientResponse response, TokenUsage usage) {
        Round round = handle.round;
        if (usage != null) {
            round.usage = usage;
        }
        if (response == null || response.chatResponse() == null) {
            return;
        }
        String model = response.chatResponse().getMetadata().getModel();
        if (model != null && !model.isBlank()) {
            round.model = model;
        }
        if (response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null) {
            return;
        }
        Map<String, Object> metadata = response.chatResponse().getResult().getOutput().getMetadata();
        if (metadata == null) {
            return;
        }
        for (String key : SUMMARY_KEYS) {
            Object value = metadata.get(key);
            if (value instanceof String summary && !summary.isBlank()) {
                String sanitized = redact(summary);
                if (!round.reasoningSummaries.contains(sanitized)) {
                    round.reasoningSummaries.add(sanitized);
                }
            }
        }
    }

    synchronized void finishRound(RoundHandle handle, Throwable failure) {
        Round round = handle.round;
        round.durationMs = elapsedMillis(round.startedNanos);
        if (failure != null) {
            round.error = errorOf(failure);
        }
    }

    ToolHandle startTool(String name, String arguments) {
        ToolEvent event = new ToolEvent(eventSequence.incrementAndGet(), name, safePayload(arguments),
                Instant.now(), System.nanoTime());
        synchronized (this) {
            tools.add(event);
        }
        return new ToolHandle(event);
    }

    synchronized void finishTool(ToolHandle handle, String result, Throwable failure) {
        ToolEvent event = handle.event;
        event.durationMs = elapsedMillis(event.startedNanos);
        if (failure == null) {
            event.result = safePayload(result);
        } else {
            event.error = errorOf(failure);
        }
    }

    synchronized void complete(String answer) {
        status = "completed";
        finalAnswer = redact(answer);
    }

    synchronized void fail(String partial, Throwable failure) {
        status = "error";
        partialAnswer = redact(partial);
        error = errorOf(failure);
    }

    synchronized JsonNode snapshot() {
        ObjectNode root = json.createObjectNode();
        root.put("version", 1);
        root.put("status", status);
        root.put("acceptedAt", acceptedAt.toString());
        ArrayNode roundNodes = root.putArray("modelRounds");
        rounds.forEach(round -> roundNodes.add(roundNode(round)));
        ArrayNode toolNodes = root.putArray("tools");
        tools.stream().sorted(java.util.Comparator.comparingLong(tool -> tool.sequence))
                .forEach(tool -> toolNodes.add(toolNode(tool)));
        if (finalAnswer != null) {
            root.put("finalAnswer", finalAnswer);
        }
        if (partialAnswer != null) {
            root.put("partialAnswer", partialAnswer);
        }
        if (error != null) {
            root.set("error", errorNode(error));
        }
        return root;
    }

    private List<JsonNode> messages(ChatClientRequest request) {
        if (request == null || request.prompt() == null) {
            return List.of();
        }
        return request.prompt().getInstructions().stream().map(this::messageNode).toList();
    }

    private JsonNode messageNode(Message message) {
        ObjectNode node = json.createObjectNode();
        node.put("role", message.getMessageType().getValue());
        boolean thinking = message instanceof AssistantMessage assistant
                && isThinking(assistant.getMetadata());
        if (message.getText() != null && !thinking) {
            node.put("content", redact(message.getText()));
        }
        if (message instanceof AssistantMessage assistant && !assistant.getToolCalls().isEmpty()) {
            ArrayNode calls = node.putArray("toolCalls");
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                ObjectNode value = calls.addObject();
                value.put("id", call.id());
                value.put("name", call.name());
                value.put("arguments", safePayload(call.arguments()));
            }
        }
        if (message instanceof ToolResponseMessage toolResponse) {
            ArrayNode responses = node.putArray("toolResponses");
            for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                ObjectNode value = responses.addObject();
                value.put("id", response.id());
                value.put("name", response.name());
                value.put("result", safePayload(response.responseData()));
            }
        }
        return node;
    }

    private ObjectNode roundNode(Round round) {
        ObjectNode node = json.createObjectNode();
        node.put("round", round.number);
        node.put("sequence", round.sequence);
        node.put("startedAt", round.startedAt.toString());
        if (round.durationMs != null) {
            node.put("durationMs", round.durationMs);
        }
        ArrayNode prompts = node.putArray("messages");
        round.messages.forEach(prompts::add);
        if (round.model != null) {
            node.put("model", round.model);
        }
        if (round.usage != null) {
            node.set("tokens", tokenNode(round.usage));
        }
        if (!round.reasoningSummaries.isEmpty()) {
            ArrayNode summaries = node.putArray("reasoningSummaries");
            round.reasoningSummaries.forEach(summaries::add);
        }
        if (round.error != null) {
            node.set("error", errorNode(round.error));
        }
        return node;
    }

    private ObjectNode toolNode(ToolEvent tool) {
        ObjectNode node = json.createObjectNode();
        node.put("sequence", tool.sequence);
        node.put("name", tool.name);
        node.put("arguments", tool.arguments);
        node.put("startedAt", tool.startedAt.toString());
        if (tool.durationMs != null) {
            node.put("durationMs", tool.durationMs);
        }
        if (tool.result != null) {
            node.put("result", tool.result);
        }
        if (tool.error != null) {
            node.set("error", errorNode(tool.error));
        }
        return node;
    }

    private ObjectNode tokenNode(TokenUsage usage) {
        ObjectNode node = json.createObjectNode();
        putNullable(node, "input", usage.inputTokens());
        putNullable(node, "cached", usage.cachedTokens());
        putNullable(node, "cacheWrite", usage.cacheWriteTokens());
        putNullable(node, "output", usage.outputTokens());
        return node;
    }

    private static void putNullable(ObjectNode node, String field, Integer value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private ObjectNode errorNode(ErrorEvent value) {
        ObjectNode node = json.createObjectNode();
        node.put("type", value.type);
        if (value.message != null) {
            node.put("message", value.message);
        }
        return node;
    }

    private static ErrorEvent errorOf(Throwable failure) {
        String message = failure.getMessage();
        if (message != null) {
            message = redact(message);
            if (message.length() > MAX_ERROR_CHARS) {
                message = message.substring(0, MAX_ERROR_CHARS);
            }
        }
        return new ErrorEvent(failure.getClass().getName(), message);
    }

    private String safePayload(String value) {
        if (value == null) {
            return null;
        }
        try {
            JsonNode parsed = json.readTree(value);
            return parsed == null ? redact(value) : json.writeValueAsString(redactNode(parsed));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return redact(value);
        }
    }

    private JsonNode redactNode(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                object.set(field.getKey(), sensitive(field.getKey())
                        ? json.getNodeFactory().textNode("[REDACTED]")
                        : redactNode(field.getValue()));
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                array.set(i, redactNode(array.get(i)));
            }
        } else if (node.isTextual()) {
            return json.getNodeFactory().textNode(redact(node.textValue()));
        }
        return node;
    }

    private static boolean sensitive(String key) {
        String folded = key.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "");
        return folded.contains("authorization") || folded.contains("apikey")
                || folded.contains("password") || folded.contains("secret")
                || folded.equals("jwt") || folded.endsWith("token");
    }

    private static String redact(String value) {
        if (value == null) {
            return null;
        }
        Matcher quoted = QUOTED_PAIR.matcher(value);
        StringBuilder sanitized = new StringBuilder();
        while (quoted.find()) {
            String replacement = sensitive(quoted.group(1))
                    ? "\"" + quoted.group(1) + "\"" + quoted.group(2) + "\"[REDACTED]\""
                    : quoted.group();
            quoted.appendReplacement(sanitized, Matcher.quoteReplacement(replacement));
        }
        quoted.appendTail(sanitized);
        return sanitized.toString()
                .replaceAll("(?i)bearer\\s+[a-z0-9._~+/=-]+", "Bearer [REDACTED]")
                .replaceAll("(?i)(api[-_ ]?key|authorization|password|secret|jwt|token)\\s*[:=]\\s*[^\\s,;]+",
                        "$1=[REDACTED]")
                .replaceAll("\\bsk-[A-Za-z0-9_-]{8,}\\b", "[REDACTED]");
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    static boolean isHiddenReasoning(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return false;
        }
        return isThinking(response.getResult().getOutput().getMetadata());
    }

    private static boolean isThinking(Map<String, Object> metadata) {
        return metadata != null && (Boolean.TRUE.equals(metadata.get("thinking"))
                || Boolean.TRUE.equals(metadata.get("isThought")));
    }

    static final class RoundHandle {
        private final Round round;
        private RoundHandle(Round round) { this.round = round; }
    }

    static final class ToolHandle {
        private final ToolEvent event;
        private ToolHandle(ToolEvent event) { this.event = event; }
    }

    private static final class Round {
        private final int number;
        private final long sequence;
        private final Instant startedAt;
        private final long startedNanos;
        private final List<JsonNode> messages;
        private final List<String> reasoningSummaries = new ArrayList<>();
        private Long durationMs;
        private String model;
        private TokenUsage usage;
        private ErrorEvent error;

        private Round(int number, long sequence, Instant startedAt, long startedNanos,
                      List<JsonNode> messages) {
            this.number = number;
            this.sequence = sequence;
            this.startedAt = startedAt;
            this.startedNanos = startedNanos;
            this.messages = messages;
        }
    }

    private static final class ToolEvent {
        private final long sequence;
        private final String name;
        private final String arguments;
        private final Instant startedAt;
        private final long startedNanos;
        private Long durationMs;
        private String result;
        private ErrorEvent error;

        private ToolEvent(long sequence, String name, String arguments, Instant startedAt,
                          long startedNanos) {
            this.sequence = sequence;
            this.name = name;
            this.arguments = arguments;
            this.startedAt = startedAt;
            this.startedNanos = startedNanos;
        }
    }

    private record ErrorEvent(String type, String message) {}
}
