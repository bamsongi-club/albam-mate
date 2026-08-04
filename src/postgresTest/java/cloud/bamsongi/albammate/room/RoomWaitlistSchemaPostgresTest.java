package cloud.bamsongi.albammate.room;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
class RoomWaitlistSchemaPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_waitlist_schema_test");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void 대기열_스키마는_복합_PK_FK_CHECK_sequence와_WAITING_부분_인덱스를_만든다() {
		assertEquals(
			"room_id,user_id",
			jdbcTemplate.queryForObject(
				"""
					select string_agg(column_name, ',' order by ordinal_position)
					from information_schema.key_column_usage
					where constraint_name = 'pk_room_waitlists'
					""",
				String.class));
		assertEquals(
			2,
			jdbcTemplate.queryForObject(
				"""
					select count(*)
					from information_schema.table_constraints
					where table_name = 'room_waitlists' and constraint_type = 'FOREIGN KEY'
					""",
				Integer.class));
		assertEquals(
			2,
			jdbcTemplate.queryForObject(
				"""
					select count(*)
					from information_schema.table_constraints
					where table_name = 'room_waitlists'
					  and constraint_name in ('ck_room_waitlists_status', 'ck_room_waitlists_queue_order_positive')
					""",
				Integer.class));
		assertEquals(
			1L,
			jdbcTemplate.queryForObject("select nextval('room_waitlist_queue_order_seq')", Long.class));

		assertIndex(
			"uq_room_waitlists_waiting_room_queue_order",
			"room_id,queue_order",
			true);
		assertIndex(
			"idx_room_waitlists_waiting_user_room",
			"user_id,room_id",
			false);
		assertEquals(2, jdbcTemplate.queryForObject("""
			select count(*)
			from information_schema.referential_constraints
			where constraint_name in ('fk_room_waitlists_room', 'fk_room_waitlists_user')
			  and delete_rule = 'NO ACTION'
			""", Integer.class));
		assertEquals(
			"room_id:rooms:id",
			jdbcTemplate.queryForObject("""
				select kcu.column_name || ':' || ccu.table_name || ':' || ccu.column_name
				from information_schema.key_column_usage kcu
				join information_schema.constraint_column_usage ccu
				  on kcu.constraint_name = ccu.constraint_name
				where kcu.constraint_name = 'fk_room_waitlists_room'
				""", String.class));
		assertEquals(
			"user_id:users:id",
			jdbcTemplate.queryForObject("""
				select kcu.column_name || ':' || ccu.table_name || ':' || ccu.column_name
				from information_schema.key_column_usage kcu
				join information_schema.constraint_column_usage ccu
				  on kcu.constraint_name = ccu.constraint_name
				where kcu.constraint_name = 'fk_room_waitlists_user'
				""", String.class));
		assertEquals(
			"WAITING,PROMOTED,CANCELED,EXPIRED,ROOM_CANCELED",
			jdbcTemplate.queryForObject("""
				select string_agg(captured.value[1], ',' order by array_position(
				    array['WAITING', 'PROMOTED', 'CANCELED', 'EXPIRED', 'ROOM_CANCELED'], captured.value[1]))
				from pg_constraint
				cross join regexp_matches(
				    pg_get_constraintdef(oid), '''([A-Z_]+)''', 'g') as captured(value)
				where conname = 'ck_room_waitlists_status'
				""", String.class));
		assertEquals(
			"CHECK ((queue_order > 0))",
			jdbcTemplate.queryForObject(
				"select pg_get_constraintdef(oid) from pg_constraint where conname = 'ck_room_waitlists_queue_order_positive'",
				String.class));
		assertEquals("1",
			jdbcTemplate.queryForObject(
				"select start_value::text from pg_sequences where sequencename = 'room_waitlist_queue_order_seq'",
				String.class));
		assertEquals("1",
			jdbcTemplate.queryForObject(
				"select increment_by::text from pg_sequences where sequencename = 'room_waitlist_queue_order_seq'",
				String.class));
		assertEquals(1L, jdbcTemplate.queryForObject(
			"select cache_size from pg_sequences where sequencename = 'room_waitlist_queue_order_seq'", Long.class));
	}

	private void assertIndex(String indexName, String expectedColumns, boolean expectedUnique) {
		IndexMetadata index = jdbcTemplate.queryForObject("""
			select string_agg(attribute.attname, ',' order by key.ordinality) as columns,
			    index_catalog.indisunique as unique_index,
			    pg_get_expr(index_catalog.indpred, index_catalog.indrelid) as predicate
			from pg_index index_catalog
			join pg_class index_class on index_class.oid = index_catalog.indexrelid
			join unnest(index_catalog.indkey) with ordinality key(attribute_number, ordinality) on true
			join pg_attribute attribute on attribute.attrelid = index_catalog.indrelid
			    and attribute.attnum = key.attribute_number
			where index_class.relname = ?
			group by index_catalog.indisunique, index_catalog.indpred, index_catalog.indrelid
			""",
			(rs, rowNumber) -> new IndexMetadata(
				rs.getString("columns"), rs.getBoolean("unique_index"), rs.getString("predicate")),
			indexName);

		assertEquals(expectedColumns, index.columns());
		assertEquals(expectedUnique, index.unique());
		assertEquals("status='WAITING'", normalizePredicate(index.predicate()));
	}

	private String normalizePredicate(String predicate) {
		return predicate
			.replace("::character varying", "")
			.replace("::text", "")
			.replace("(", "")
			.replace(")", "")
			.replace(" ", "");
	}

	private record IndexMetadata(String columns, boolean unique, String predicate) {
	}
}
