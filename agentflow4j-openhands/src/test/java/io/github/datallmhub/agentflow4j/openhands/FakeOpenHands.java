package io.github.datallmhub.agentflow4j.openhands;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpServer;

/**
 * A stand-in OpenHands server on a local port: enough of the V1 API to drive
 * the client and the agent through a whole conversation, with no network.
 *
 * <p>Statuses are scripted per conversation, one per poll, so a test can walk
 * a run from {@code running} to {@code finished} deterministically.
 */
final class FakeOpenHands implements AutoCloseable {

    private final HttpServer server;
    private final Deque<String> executionStatuses = new ArrayDeque<>();
    private final Map<String, Integer> callCounts = new ConcurrentHashMap<>();
    private final List<String> requests = new ArrayList<>();

    private String startStatus = "WAITING_FOR_SANDBOX";
    private String conversationIdOnStart = null;
    private String sandboxStatus = "RUNNING";
    private double accumulatedCost = 0.0;
    private Integer prNumber = null;
    private int failWithStatus = 0;
    private String failBody = "{\"error\":\"boom\"}";

    FakeOpenHands() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        server.createContext("/api/v1/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            synchronized (requests) {
                requests.add(exchange.getRequestMethod() + " " + path + (query == null ? "" : "?" + query));
            }
            callCounts.merge(path, 1, Integer::sum);
            if (failWithStatus > 0) {
                respond(exchange, failWithStatus, failBody);
                return;
            }
            if (path.equals("/api/v1/app-conversations") && exchange.getRequestMethod().equals("POST")) {
                respond(exchange, 200, """
                        {"id":"task-1","status":"%s"%s}"""
                        .formatted(startStatus, conversationIdOnStart == null ? ""
                                : ",\"app_conversation_id\":\"" + conversationIdOnStart + "\",\"sandbox_id\":\"sb-1\""));
                return;
            }
            if (path.equals("/api/v1/app-conversations/start-tasks")) {
                respond(exchange, 200, """
                        [{"id":"task-1","status":"READY","app_conversation_id":"conv-1","sandbox_id":"sb-1"}]""");
                return;
            }
            if (path.equals("/api/v1/app-conversations") && exchange.getRequestMethod().equals("GET")) {
                String status = executionStatuses.isEmpty() ? "finished" : executionStatuses.poll();
                respond(exchange, 200, """
                        [{"id":"conv-1","sandbox_id":"sb-1","sandbox_status":"%s","execution_status":"%s",
                          "selected_repository":"acme/billing","selected_branch":"openhands/fix-42",
                          %s"last_agent_message":"opened a pull request",
                          "metrics":{"accumulated_cost":%s,
                                     "accumulated_token_usage":{"prompt_tokens":120,"completion_tokens":45}}}]"""
                        .formatted(sandboxStatus, status,
                                prNumber == null ? "" : "\"pr_number\":" + prNumber + ",", accumulatedCost));
                return;
            }
            respond(exchange, 200, "{}");
        });
        server.start();
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    FakeOpenHands scriptStatuses(String... statuses) {
        executionStatuses.addAll(List.of(statuses));
        return this;
    }

    FakeOpenHands readyOnStart() {
        this.startStatus = "READY";
        this.conversationIdOnStart = "conv-1";
        return this;
    }

    FakeOpenHands sandboxStatus(String status) {
        this.sandboxStatus = status;
        return this;
    }

    FakeOpenHands cost(double cost) {
        this.accumulatedCost = cost;
        return this;
    }

    FakeOpenHands pullRequest(int number) {
        this.prNumber = number;
        return this;
    }

    FakeOpenHands failEveryCallWith(int status, String body) {
        this.failWithStatus = status;
        this.failBody = body;
        return this;
    }

    int callsTo(String path) {
        return callCounts.getOrDefault(path, 0);
    }

    List<String> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
