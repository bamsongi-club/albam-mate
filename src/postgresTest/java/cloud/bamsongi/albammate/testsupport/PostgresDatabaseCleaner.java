package cloud.bamsongi.albammate.testsupport;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

public final class PostgresDatabaseCleaner {

	private static final String FLYWAY_HISTORY_TABLE = "flyway_schema_history";

	private PostgresDatabaseCleaner() {}

	public static void clean(DataSource dataSource) throws SQLException {
		try (Connection connection = dataSource.getConnection();
			Statement statement = connection.createStatement()) {
			List<String> tables = userTables(statement);
			if (!tables.isEmpty()) {
				statement.execute("TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
			}
			resetSequences(statement);
			restoreFlywaySeedData(statement);
		}
	}

	private static void resetSequences(Statement statement) throws SQLException {
		List<SequenceDefinition> sequences = new ArrayList<>();
		try (ResultSet resultSet = statement.executeQuery("""
			select sequencename, start_value
			from pg_catalog.pg_sequences
			where schemaname = 'public'
			order by sequencename
			""")) {
			while (resultSet.next()) {
				sequences.add(new SequenceDefinition(
					resultSet.getString("sequencename"), resultSet.getLong("start_value")));
			}
		}
		for (SequenceDefinition sequence : sequences) {
			statement.execute("ALTER SEQUENCE " + qualifiedName(sequence.name())
				+ " RESTART WITH " + sequence.startValue());
		}
	}

	private static void restoreFlywaySeedData(Statement statement) throws SQLException {
		statement.executeUpdate("""
			insert into room_status_correction_progress (
			    job_name, turn_cutoff, cursor_due_at, cursor_room_id,
			    progress_version, execution_generation
			) values ('room-status-correction', null, null, null, 0, 0)
			""");
		statement.executeUpdate("""
			insert into chat_system_message_activation (gate_name, enabled_at, updated_at)
			values ('chat-system-message', null, current_timestamp)
			""");
	}

	private static List<String> userTables(Statement statement) throws SQLException {
		List<String> tables = new ArrayList<>();
		try (ResultSet resultSet = statement.executeQuery("""
			select tablename
			from pg_catalog.pg_tables
			where schemaname = 'public'
			  and tablename <> 'flyway_schema_history'
			order by tablename
			""")) {
			while (resultSet.next()) {
				tables.add(qualifiedTable(resultSet.getString("tablename")));
			}
		}
		return tables;
	}

	private static String qualifiedTable(String tableName) {
		return qualifiedName(tableName);
	}

	private static String qualifiedName(String objectName) {
		return "\"public\".\"" + objectName.replace("\"", "\"\"") + "\"";
	}

	private record SequenceDefinition(String name, long startValue) {
	}
}
