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
			if (tables.isEmpty()) {
				return;
			}
			statement.execute("TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
		}
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
		return "\"public\".\"" + tableName.replace("\"", "\"\"") + "\"";
	}
}
