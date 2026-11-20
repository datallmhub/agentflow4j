package io.github.datallmhub.agentflow4j.playground;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentEvent;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

class PlaygroundIntegrationTests {

    @SpringBootApplication
    static class TestApp {

        @Bean
        Agent echo() {
            return ctx -> AgentResult.ofText("ECHO");
        }

        @Bean
        Agent streamer() {
            return new Agent() {
                @Override
                public AgentResult execute(io.github.datallmhub.agentflow4j.core.AgentContext context) {
                    return AgentResult.ofText("hello world");
                }
                @Override
                public Flux<AgentEvent> executeStream(io.github.datallmhub.agentflow4j.core.AgentContext context) {
                    return Flux.just(
                            AgentEvent.token("hello "),
                            AgentEvent.token("world"),
                            AgentEvent.completed(AgentResult.ofText("hello world")));
                }
            };
        }
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplication(TestApp.class)
                .run("--server.port=0", "--spring.main.web-application-type=servlet");
    }

    @Test
    void indexListsRegisteredAgents() throws Exception {
        try (ConfigurableApplicationContext ctx = start()) {
            int port = port(ctx);
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/playground")).build(),
                            HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains("agentflow4j playground")
                    .contains("echo")
                    .contains("streamer");
        }
    }

    @Test
    void chatStreamsTokensThenCompleted() throws Exception {
        try (ConfigurableApplicationContext ctx = start()) {
            int port = port(ctx);
            String payload = readSse(port, "streamer", "hi");
            assertThat(payload)
                    .contains("event:token")
                    // Spring Framework 7 writes a space after the colon; SSE clients ignore it.
                    .containsPattern("data: ?hello ")
                    .containsPattern("data: ?world")
                    .contains("event:completed");
        }
    }

    @Test
    void unknownAgentEmitsError() throws Exception {
        try (ConfigurableApplicationContext ctx = start()) {
            int port = port(ctx);
            String payload = readSse(port, "nope", "hi");
            assertThat(payload)
                    .contains("event:error")
                    .contains("unknown agent");
        }
    }

    /**
     * Reads an SSE stream tolerantly: collects bytes until the server closes
     * the connection. The HttpURLConnection chunked decoder throws when an
     * SSE producer closes the stream without a final zero-length chunk, but
     * the events that arrived before the close are valid — assert on those.
     */
    private static String readSse(int port, String agent, String message) throws IOException {
        String url = "http://localhost:" + port + "/playground/api/chat"
                + "?agent=" + URLEncoder.encode(agent, StandardCharsets.UTF_8)
                + "&message=" + URLEncoder.encode(message, StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(5000);

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream is = conn.getInputStream()) {
            byte[] chunk = new byte[1024];
            int n;
            while ((n = is.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
        } catch (IOException ignored) {
            // SSE producer may close the connection abruptly; we keep what arrived.
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static int port(ConfigurableApplicationContext ctx) {
        return Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port"));
    }
}
