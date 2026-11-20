package example.claimqueue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.sql.DataSource;

/**
 * Small reference queue on a relational database through JDBC and ANSI SQL, not an AGS production replacement.
 *
 * <p>Protects the database result publication with an owner generation and a lease. It does not provide exactly-once execution of external effects.
 * All times are injected by the caller as integers; a production clock and restart policy are out of scope.
 *
 * <p>A claim reads one claimable task and then takes it with one conditional UPDATE whose condition repeats the claimable test and the generation that was read.
 * The database lets one of several concurrent updates change the row; the others see the new row version, match nothing and read again. The number of changed
 * rows decides who owns the task, never the earlier read. A result is published by one conditional UPDATE that names the owner, the generation and a lease
 * that has not run out.
 */
public final class ClaimQueue {
    private static final Pattern SCHEMA_NAME = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    /** What a claimant holds: the task, the owner and the ownership generation of this claim. */
    public record Token(long taskId, String owner, long generation) {
    }

    private final DataSource dataSource;
    private final String tasks;

    /**
     * @param dataSource where the tasks live; every operation takes its own connection
     * @param schema the existing schema that holds (or will hold) the {@code tasks} table; an identifier, validated because a name cannot be a bind parameter
     */
    public ClaimQueue(DataSource dataSource, String schema) {
        if (dataSource == null || schema == null || !SCHEMA_NAME.matcher(schema).matches()) {
            throw new IllegalArgumentException("A data source and a lower-case schema identifier are required");
        }
        this.dataSource = dataSource;
        this.tasks = schema + ".tasks";
    }

    /** Creates the {@code tasks} table; the states a task may have are a CHECK constraint, the key a PRIMARY KEY. */
    public void initialize() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + tasks + " ("
                    + "id BIGINT NOT NULL PRIMARY KEY, "
                    + "state VARCHAR(16) NOT NULL CHECK (state IN ('pending', 'running', 'done')), "
                    + "owner VARCHAR(64), "
                    + "generation BIGINT NOT NULL DEFAULT 0, "
                    + "lease_until BIGINT NOT NULL DEFAULT 0, "
                    + "result VARCHAR(256))");
        } catch (SQLException failure) {
            throw new IllegalStateException("The tasks table could not be created", failure);
        }
    }

    /** The oldest claimable task (pending, or running with a lease that ran out by {@code now}) taken for {@code owner}, or empty when there is none. */
    public Optional<Token> claim(String owner, long now, long lease) {
        if (owner == null || owner.isBlank() || lease <= 0) {
            throw new IllegalArgumentException("An owner and a positive lease are required");
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                Optional<Token> token = claimOldest(connection, owner, now, lease);
                connection.commit();
                return token;
            } catch (SQLException | RuntimeException failure) {
                rollBack(connection, failure);
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("The claim failed", failure);
        }
    }

    private Optional<Token> claimOldest(Connection connection, String owner, long now, long lease) throws SQLException {
        while (true) {
            long taskId;
            long generation;
            try (PreparedStatement read = connection.prepareStatement("SELECT id, generation FROM " + tasks
                    + " WHERE state = 'pending' OR (state = 'running' AND lease_until <= ?) ORDER BY id FETCH FIRST 1 ROWS ONLY")) {
                read.setLong(1, now);
                try (ResultSet row = read.executeQuery()) {
                    if (!row.next()) {
                        return Optional.empty();
                    }
                    taskId = row.getLong(1);
                    generation = row.getLong(2);
                }
            }
            try (PreparedStatement take = connection.prepareStatement("UPDATE " + tasks
                    + " SET state = 'running', owner = ?, generation = generation + 1, lease_until = ?"
                    + " WHERE id = ? AND generation = ? AND (state = 'pending' OR (state = 'running' AND lease_until <= ?))")) {
                take.setString(1, owner);
                take.setLong(2, now + lease);
                take.setLong(3, taskId);
                take.setLong(4, generation);
                take.setLong(5, now);
                if (take.executeUpdate() == 1) {
                    return Optional.of(new Token(taskId, owner, generation + 1));
                }
            }
            // Another claimant changed the row between the read and the update: it won this task, and the next read decides what is still claimable.
        }
    }

    /** Publishes {@code result} when {@code token} is still the current owner and its lease has not run out at {@code now}; false when it is not. */
    public boolean finish(Token token, String result, long now) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement publish = connection.prepareStatement("UPDATE " + tasks
                + " SET state = 'done', result = ? WHERE id = ? AND owner = ? AND generation = ? AND state = 'running' AND lease_until > ?")) {
            publish.setString(1, result);
            publish.setLong(2, token.taskId());
            publish.setString(3, token.owner());
            publish.setLong(4, token.generation());
            publish.setLong(5, now);
            return publish.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new IllegalStateException("The result could not be published", failure);
        }
    }

    private static void rollBack(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }
}
