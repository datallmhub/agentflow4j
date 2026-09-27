package io.github.datallmhub.agentflow4j.openhands;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.datallmhub.agentflow4j.core.Experimental;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A thin client over the OpenHands V1 conversation API, built on the JDK
 * {@link HttpClient} so this module stays free of any Spring Web dependency.
 * Every call has a connect timeout and a request timeout.
 *
 * <p>The same API serves OpenHands Cloud, the enterprise edition and a
 * self-hosted server:
 *
 * <pre>{@code
 * OpenHandsClient cloud = OpenHandsClient.cloud(System.getenv("OPENHANDS_API_KEY"));
 * OpenHandsClient local = OpenHandsClient.of(URI.create("http://localhost:3000"), apiKey);
 * }</pre>
 */
@Experimental
public final class OpenHandsClient {

    private static final Logger log = LoggerFactory.getLogger(OpenHandsClient.class);
    private static final URI CLOUD = URI.create("https://app.all-hands.dev");

    private final URI baseUrl;
    private final String apiKey;
    private final HttpClient http;
    private final Duration requestTimeout;
    private final ObjectMapper json = new ObjectMapper();

    private OpenHandsClient(Builder b) {
        this.baseUrl = b.baseUrl;
        this.apiKey = Objects.requireNonNull(b.apiKey, "apiKey");
        this.requestTimeout = b.requestTimeout;
        this.http = b.httpClient != null ? b.httpClient
                : HttpClient.newBuilder().connectTimeout(b.connectTimeout).build();
    }

    public static OpenHandsClient cloud(String apiKey) {
        return builder().apiKey(apiKey).build();
    }

    public static OpenHandsClient of(URI baseUrl, String apiKey) {
        return builder().baseUrl(baseUrl).apiKey(apiKey).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Requests a conversation for {@code task}; the sandbox starts in the background. */
    public OpenHandsStartTask startConversation(String task, @Nullable String repository) {
        Objects.requireNonNull(task, "task");
        ObjectNode message = json.createObjectNode();
        message.putArray("content").addObject().put("type", "text").put("text", task);
        ObjectNode body = json.createObjectNode();
        body.set("initial_message", message);
        if (repository != null && !repository.isBlank()) {
            body.put("selected_repository", repository);
        }
        JsonNode response = send("POST", "/api/v1/app-conversations", body);
        return startTaskOf(response);
    }

    /** Polls a start task until it carries a conversation id. */
    public OpenHandsStartTask startTask(String startTaskId) {
        Objects.requireNonNull(startTaskId, "startTaskId");
        JsonNode response = send("GET", "/api/v1/app-conversations/start-tasks?ids=" + enc(startTaskId), null);
        return startTaskOf(first(response));
    }

    /** Reads a conversation's status, metrics and result. */
    public OpenHandsConversation conversation(String conversationId) {
        Objects.requireNonNull(conversationId, "conversationId");
        JsonNode node = first(send("GET", "/api/v1/app-conversations?ids=" + enc(conversationId), null));
        JsonNode metrics = node.path("metrics");
        JsonNode tokens = metrics.path("accumulated_token_usage");
        return new OpenHandsConversation(
                text(node, "id", conversationId),
                text(node, "sandbox_id", null),
                OpenHandsStatus.of(node.path("execution_status").asText(null)),
                OpenHandsStatus.of(node.path("sandbox_status").asText(null)),
                text(node, "last_agent_message", text(node, "title", null)),
                text(node, "selected_repository", null),
                text(node, "selected_branch", null),
                node.hasNonNull("pr_number") ? node.get("pr_number").asInt() : null,
                metrics.path("accumulated_cost").asDouble(0.0),
                tokens.path("prompt_tokens").asLong(0L),
                tokens.path("completion_tokens").asLong(0L));
    }

    /** Sends a follow-up message, for instance after a human unblocked the agent. */
    public void sendMessage(String conversationId, String message) {
        Objects.requireNonNull(conversationId, "conversationId");
        ObjectNode content = json.createObjectNode();
        content.putArray("content").addObject().put("type", "text").put("text", message);
        ObjectNode body = json.createObjectNode();
        body.set("message", content);
        send("POST", "/api/v1/app-conversations/" + enc(conversationId) + "/send-message", body);
    }

    /** Pauses a sandbox, which is how a node stops paying for a run it is giving up on. */
    public void pauseSandbox(String sandboxId) {
        Objects.requireNonNull(sandboxId, "sandboxId");
        send("POST", "/api/v1/sandboxes/" + enc(sandboxId) + "/pause", null);
    }

    private OpenHandsStartTask startTaskOf(JsonNode node) {
        return new OpenHandsStartTask(
                text(node, "id", ""),
                OpenHandsStatus.of(node.path("status").asText(null)),
                text(node, "app_conversation_id", null),
                text(node, "sandbox_id", null));
    }

    private JsonNode send(String method, String path, @Nullable JsonNode body) {
        URI uri = baseUrl.resolve(path);
        HttpRequest.BodyPublisher publisher;
        try {
            publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        }
        catch (IOException ex) {
            throw new OpenHandsException("Could not serialize the request to " + path, ex);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method(method, publisher)
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException ex) {
            throw new OpenHandsException("OpenHands call failed: " + method + " " + path, ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OpenHandsException("Interrupted calling OpenHands: " + method + " " + path, ex);
        }
        log.debug("openhands.call: method={} path={} status={}", method, path, response.statusCode());
        if (response.statusCode() >= 300) {
            throw new OpenHandsException(method + " " + path + " returned " + response.statusCode(),
                    response.statusCode(), response.body());
        }
        if (response.body() == null || response.body().isBlank()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(response.body());
        }
        catch (IOException ex) {
            throw new OpenHandsException("Could not read the response of " + method + " " + path, ex);
        }
    }

    /** The batch endpoints answer with an array, or with {@code items} when paginated. */
    private static JsonNode first(JsonNode response) {
        if (response.isArray()) {
            if (response.isEmpty()) {
                throw new OpenHandsException("OpenHands returned no conversation", 404, null);
            }
            return response.get(0);
        }
        JsonNode items = response.path("items");
        if (items.isArray() && !items.isEmpty()) {
            return items.get(0);
        }
        return response;
    }

    @Nullable
    private static String text(JsonNode node, String field, @Nullable String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? fallback : value.asText();
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static final class Builder {
        private URI baseUrl = CLOUD;
        private String apiKey;
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(30);
        @Nullable private HttpClient httpClient;

        public Builder baseUrl(URI baseUrl) {
            this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl");
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
            return this;
        }

        /** For tests, or to share a tuned client (proxy, TLS, executor). */
        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public OpenHandsClient build() {
            return new OpenHandsClient(this);
        }
    }
}
