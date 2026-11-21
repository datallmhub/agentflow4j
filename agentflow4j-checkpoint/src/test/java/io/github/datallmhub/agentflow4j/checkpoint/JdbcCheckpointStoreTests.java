package io.github.datallmhub.agentflow4j.checkpoint;

import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.StateKey;
import io.github.datallmhub.agentflow4j.graph.Checkpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcCheckpointStoreTests {

    public record Note(String text) {}

    private static final StateKey<Note> NOTE = StateKey.of("note", Note.class);

    private DriverManagerDataSource ds;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager txManager;
    private JdbcCheckpointStore store;

    @BeforeEach
    void setUp() {
        ds = new DriverManagerDataSource(
                "jdbc:h2:mem:checkpoint-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa", "");
        ds.setDriverClassName("org.h2.Driver");
        jdbc = new JdbcTemplate(ds);
        txManager = new DataSourceTransactionManager(ds);
        JacksonCheckpointCodec codec = new JacksonCheckpointCodec(
                new StateTypeRegistry().register(NOTE));
        store = new JdbcCheckpointStore(jdbc, txManager, codec);
        store.createTableIfMissing();
    }

    @AfterEach
    void tearDown() throws Exception {
        jdbc.execute("DROP ALL OBJECTS");
        ((DataSource) ds).getConnection().close();
    }

    @Test
    void saveAndLoadRoundTripsCheckpoint() {
        AgentContext ctx = AgentContext.empty()
                .withMessages(List.of(new UserMessage("hi")))
                .with(NOTE, new Note("remember this"));
        Checkpoint cp = new Checkpoint("run-a", "plan", ctx, 2, null);

        store.save(cp);
        Optional<Checkpoint> loaded = store.load("run-a");

        assertThat(loaded).isPresent();
        assertThat(loaded.get().runId()).isEqualTo("run-a");
        assertThat(loaded.get().nextNode()).isEqualTo("plan");
        assertThat(loaded.get().iterations()).isEqualTo(2);
        assertThat(loaded.get().context().messages()).hasSize(1);
        assertThat(loaded.get().context().get(NOTE)).isEqualTo(new Note("remember this"));
    }

    @Test
    void saveOverwritesExistingRowWithoutDuplicate() {
        Checkpoint first = new Checkpoint("run-b", "plan", AgentContext.empty(), 1, null);
        Checkpoint second = new Checkpoint("run-b", "act",
                AgentContext.empty().with(NOTE, new Note("v2")), 5, null);

        store.save(first);
        store.save(second);

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_checkpoint WHERE run_id = ?", Integer.class, "run-b");
        assertThat(rows).isEqualTo(1);

        Checkpoint loaded = store.load("run-b").orElseThrow();
        assertThat(loaded.nextNode()).isEqualTo("act");
        assertThat(loaded.iterations()).isEqualTo(5);
        assertThat(loaded.context().get(NOTE)).isEqualTo(new Note("v2"));
    }

    @Test
    void loadReturnsEmptyWhenRunIdNotFound() {
        assertThat(store.load("missing")).isEmpty();
    }

    @Test
    void deleteRemovesCheckpointRow() {
        store.save(new Checkpoint("run-c", "plan", AgentContext.empty(), 0, null));
        assertThat(store.load("run-c")).isPresent();

        store.delete("run-c");

        assertThat(store.load("run-c")).isEmpty();
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_checkpoint WHERE run_id = ?", Integer.class, "run-c");
        assertThat(rows).isEqualTo(0);
    }

    @Test
    void customTableNameIsHonoured() {
        JdbcCheckpointStore custom = new JdbcCheckpointStore(jdbc, txManager,
                new JacksonCheckpointCodec(new StateTypeRegistry().register(NOTE)),
                "custom_ckpt");
        custom.createTableIfMissing();

        custom.save(new Checkpoint("run-d", "plan", AgentContext.empty(), 0, null));

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM custom_ckpt WHERE run_id = ?", Integer.class, "run-d");
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void illegalTableNameIsRejected() {
        assertThatThrownBy(() -> new JdbcCheckpointStore(jdbc, txManager,
                new JacksonCheckpointCodec(new StateTypeRegistry()),
                "drop; table"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Illegal table name");
    }

    @Test
    void payloadColumnUsesATypeTheDatabaseHas() {
        assertThat(JdbcCheckpointStore.payloadColumnType("PostgreSQL")).isEqualTo("TEXT");
        assertThat(JdbcCheckpointStore.payloadColumnType("MySQL")).isEqualTo("LONGTEXT");
        assertThat(JdbcCheckpointStore.payloadColumnType("MariaDB")).isEqualTo("LONGTEXT");
        assertThat(JdbcCheckpointStore.payloadColumnType("H2")).isEqualTo("CLOB");
        assertThat(JdbcCheckpointStore.payloadColumnType(null)).isEqualTo("CLOB");
    }
}
