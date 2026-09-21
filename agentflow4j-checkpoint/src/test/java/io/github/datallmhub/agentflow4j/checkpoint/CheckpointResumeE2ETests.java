package io.github.datallmhub.agentflow4j.checkpoint;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.StateKey;
import io.github.datallmhub.agentflow4j.graph.AgentGraph;
import io.github.datallmhub.agentflow4j.graph.Checkpoint;
import io.github.datallmhub.agentflow4j.graph.CheckpointStore;
import io.github.datallmhub.agentflow4j.graph.ResumeOptions;
import io.github.datallmhub.agentflow4j.graph.RunOptions;
import io.github.datallmhub.agentflow4j.test.MockAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end test covering the full interrupt → persist → simulated JVM restart →
 * resume → complete → cleanup cycle against both production checkpoint backends.
 * Validates that state written before the interrupt survives Jackson
 * serialization and is visible to nodes executed after the resume.
 */
class CheckpointResumeE2ETests {

    private static final StateKey<String> ROUTE = StateKey.of("route", String.class);

    private DriverManagerDataSource ds;
    private JdbcTemplate jdbc;
    private JacksonCheckpointCodec codec;

    @BeforeEach
    void setUp() {
        codec = new JacksonCheckpointCodec(new StateTypeRegistry().register(ROUTE));
        ds = new DriverManagerDataSource(
                "jdbc:h2:mem:e2e-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        jdbc = new JdbcTemplate(ds);
    }

    @AfterEach
    void tearDown() throws Exception {
        jdbc.execute("DROP ALL OBJECTS");
        ((DataSource) ds).getConnection().close();
    }

    @Test
    void jdbcStoreSurvivesInterruptAndResume() {
        JdbcCheckpointStore store = new JdbcCheckpointStore(jdbc,
                new DataSourceTransactionManager(ds), codec);
        store.createTableIfMissing();

        runE2EScenario(store);
    }

    @Test
    void redisStoreSurvivesInterruptAndResume() {
        runE2EScenario(new RedisCheckpointStore(inMemoryRedis(), codec));
    }

    /**
     * Runs a 3-node graph (plan → approve → dispatch) twice against the same
     * store. The first run interrupts at "approve"; the second run is driven
     * by a freshly-built graph (new MockAgent instances, new AgentGraph) to
     * simulate a process restart — only the {@link CheckpointStore} carries
     * state across the boundary.
     */
    private void runE2EScenario(CheckpointStore store) {
        String runId = "run-" + System.nanoTime();

        // --- First run: plan writes state, approve interrupts, dispatch never fires.
        MockAgent plan1 = MockAgent.builder()
                .thenAnswer(ctx -> AgentResult.builder()
                        .text("planned")
                        .stateUpdates(Map.of(ROUTE, "team-A"))
                        .build())
                .build();
        MockAgent approve1 = MockAgent.returning(AgentResult.interrupted("need human approval"));
        MockAgent dispatch1 = MockAgent.returning("should not fire in run 1");

        AgentResult interrupted = buildGraph(store, plan1, approve1, dispatch1)
                .invoke(AgentContext.of("ticket-42"), RunOptions.ofRunId(runId));

        assertThat(interrupted.isInterrupted()).isTrue();
        assertThat(plan1.invocations()).isEqualTo(1);
        assertThat(approve1.invocations()).isEqualTo(1);
        assertThat(dispatch1.invocations()).isZero();

        // Checkpoint is persisted: nextNode == interrupted node, state survives encoding.
        Checkpoint saved = store.load(runId).orElseThrow();
        assertThat(saved.nextNode()).isEqualTo("approve");
        assertThat(saved.isInterrupted()).isTrue();
        assertThat(saved.interrupt().reason()).isEqualTo("need human approval");
        assertThat(saved.context().get(ROUTE)).isEqualTo("team-A");

        // --- Simulated JVM restart: fresh mock agents, fresh graph, same store.
        MockAgent plan2 = MockAgent.returning("plan must not re-run");
        MockAgent approve2 = MockAgent.builder()
                .thenAnswer(ctx -> AgentResult.ofText("approved by reviewer"))
                .build();
        MockAgent dispatch2 = MockAgent.builder()
                .thenAnswer(ctx -> AgentResult.ofText("dispatched to " + ctx.get(ROUTE)))
                .build();

        AgentResult completed = buildGraph(store, plan2, approve2, dispatch2)
                .resume(runId, ResumeOptions.ofMessages(new UserMessage("approved by alice")));

        assertThat(plan2.invocations()).isZero();              // plan did not re-enter
        assertThat(approve2.invocations()).isEqualTo(1);       // approve re-entered after resume
        assertThat(dispatch2.invocations()).isEqualTo(1);
        assertThat(completed.isInterrupted()).isFalse();
        assertThat(completed.hasError()).isFalse();
        assertThat(completed.text()).isEqualTo("dispatched to team-A");

        // Successful completion deletes the checkpoint.
        assertThat(store.load(runId)).isEmpty();
    }

    private AgentGraph buildGraph(CheckpointStore store,
                                  MockAgent plan, MockAgent approve, MockAgent dispatch) {
        return AgentGraph.builder()
                .addNode("plan", plan)
                .addNode("approve", approve)
                .addNode("dispatch", dispatch)
                .addEdge("plan", "approve")
                .addEdge("approve", "dispatch")
                .checkpointStore(store)
                .build();
    }

    /**
     * Stateful Mockito double over {@link RedisOperations} backed by an
     * in-memory map. Implements only the surface that
     * {@link RedisCheckpointStore} uses: {@code opsForValue().set(k, v)},
     * {@code opsForValue().set(k, v, ttl)}, {@code opsForValue().get(k)},
     * {@code delete(k)}.
     */
    @SuppressWarnings("unchecked")
    private static RedisOperations<String, String> inMemoryRedis() {
        Map<String, String> kv = new ConcurrentHashMap<>();
        RedisOperations<String, String> ops = mock(RedisOperations.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(ops.opsForValue()).thenReturn(valueOps);

        doAnswer(inv -> {
            kv.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString());

        doAnswer(inv -> {
            kv.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));

        when(valueOps.get(any())).thenAnswer(inv -> kv.get(inv.getArgument(0).toString()));

        when(ops.delete(anyString())).thenAnswer(inv ->
                kv.remove(inv.<String>getArgument(0)) != null);

        return ops;
    }
}
