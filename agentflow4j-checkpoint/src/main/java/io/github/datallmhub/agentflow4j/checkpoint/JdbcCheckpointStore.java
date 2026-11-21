package io.github.datallmhub.agentflow4j.checkpoint;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import io.github.datallmhub.agentflow4j.graph.Checkpoint;
import io.github.datallmhub.agentflow4j.graph.CheckpointStore;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcCheckpointStore implements CheckpointStore {

    public static final String DEFAULT_TABLE = "agent_checkpoint";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final CheckpointCodec codec;
    private final String table;

    public JdbcCheckpointStore(JdbcTemplate jdbc, PlatformTransactionManager txManager, CheckpointCodec codec) {
        this(jdbc, txManager, codec, DEFAULT_TABLE);
    }

    public JdbcCheckpointStore(JdbcTemplate jdbc, PlatformTransactionManager txManager,
                               CheckpointCodec codec, String table) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.table = Objects.requireNonNull(table, "table");
        if (!table.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Illegal table name: " + table);
        }
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx = new TransactionTemplate(Objects.requireNonNull(txManager, "txManager"), def);
    }

    public void createTableIfMissing() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                + "run_id VARCHAR(128) PRIMARY KEY,"
                + "payload " + payloadColumnType(databaseProductName()) + " NOT NULL,"
                + "checkpoint_version INT NOT NULL,"
                + "created_at TIMESTAMP NOT NULL,"
                + "updated_at TIMESTAMP NOT NULL)");
    }

    @Nullable
    private String databaseProductName() {
        return jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
    }

    /**
     * {@code CLOB} is the standard type, but PostgreSQL and MySQL do not have
     * it, and MySQL's {@code TEXT} stops at 64 KB, which a long conversation
     * exceeds.
     */
    static String payloadColumnType(@Nullable String databaseProductName) {
        String product = databaseProductName == null ? "" : databaseProductName.toLowerCase(Locale.ROOT);
        if (product.contains("postgresql")) {
            return "TEXT";
        }
        if (product.contains("mysql") || product.contains("mariadb")) {
            return "LONGTEXT";
        }
        return "CLOB";
    }

    @Override
    public void save(Checkpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        String payload = codec.encode(checkpoint);
        Timestamp now = Timestamp.from(Instant.now());
        tx.executeWithoutResult(status -> {
            try {
                int updated = jdbc.update(
                        "UPDATE " + table + " SET payload=?, checkpoint_version=?, updated_at=? WHERE run_id=?",
                        payload, CheckpointDto.CURRENT_VERSION, now, checkpoint.runId());
                if (updated == 0) {
                    jdbc.update(
                            "INSERT INTO " + table
                                    + " (run_id, payload, checkpoint_version, created_at, updated_at) VALUES (?,?,?,?,?)",
                            checkpoint.runId(), payload, CheckpointDto.CURRENT_VERSION, now, now);
                }
            } catch (DataAccessException ex) {
                status.setRollbackOnly();
                throw ex;
            }
        });
    }

    @Override
    public Optional<Checkpoint> load(String runId) {
        Objects.requireNonNull(runId, "runId");
        List<String> rows = jdbc.query(
                "SELECT payload FROM " + table + " WHERE run_id = ?",
                (rs, i) -> rs.getString(1),
                runId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(codec.decode(rows.get(0)));
    }

    @Override
    public void delete(String runId) {
        Objects.requireNonNull(runId, "runId");
        jdbc.update("DELETE FROM " + table + " WHERE run_id = ?", runId);
    }
}
