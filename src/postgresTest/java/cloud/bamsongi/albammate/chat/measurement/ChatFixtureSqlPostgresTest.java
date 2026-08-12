package cloud.bamsongi.albammate.chat.measurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import cloud.bamsongi.albammate.AlbamMateApplication;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class ChatFixtureSqlPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final String POSTGRES_DATABASE = "albam_mate_chat_fixture_test";
	private static final String POSTGRES_USER = "test";
	private static final String PASSWORD_HASH = "{bcrypt}$2a$10$fixturePasswordHashOnlyForK6";
	private static final String PASSWORD = "fixture-password";
	private static final Path ROOMS_SQL = Path.of("load-tests/k6/eungi/fixtures/rooms.sql").toAbsolutePath();
	private static final Path CLEANUP_SQL = Path.of("load-tests/k6/eungi/fixtures/cleanup.sql").toAbsolutePath();

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName(POSTGRES_DATABASE);

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeAll
	static void fixture_SQL을_PostgreSQL_컨테이너에_복사한다() {
		postgres.copyFileToContainer(MountableFile.forHostPath(ROOMS_SQL), "/tmp/rooms.sql");
		postgres.copyFileToContainer(MountableFile.forHostPath(CLEANUP_SQL), "/tmp/cleanup.sql");
	}

	@AfterEach
	void fixture_테스트_데이터를_정리한다() {
		for (String runId : List.of("t1-fixture", "t2-fixture", "t3-valid", "t4-isolated_a", "t4-isolatedxa",
			"t5-derived", "t6-title-collision", "t7-rerun")) {
			cleanup(runId);
		}
	}

	@Test
	void rooms_SQL은_지정_규모와_채팅_방_불변식을_만든다() {
		runRooms("t1-fixture", 1, 7, 2);

		assertThat(count("select count(*) from rooms where title = 'k6-t1-fixture-room-1'")).isEqualTo(1);
		assertThat(count("select count(*) from users where email like 'k6.t1-fixture.chat.r1.%@example.com'"))
			.isEqualTo(7);
		assertThat(count(
			"select count(*) from participations participation join rooms room on room.id = participation.room_id "
				+ "where room.title = 'k6-t1-fixture-room-1' and participation.status = 'ACTIVE'"))
			.isEqualTo(6);
		assertThat(count(
			"select count(*) from chat_messages message join chat_rooms chat_room on chat_room.id = message.chat_room_id "
				+ "join rooms room on room.id = chat_room.room_id where room.title = 'k6-t1-fixture-room-1'"))
			.isEqualTo(2);
		assertThat(count("select count(*) from rooms where title = 'k6-t1-fixture-room-1' "
			+ "and status = 'RECRUITING' and capacity = 10 and active_participant_count = 6 and start_at > current_timestamp"))
			.isEqualTo(1);
		assertThat(count(
			"select count(*) from participations participation join rooms room on room.id = participation.room_id "
				+ "where room.title = 'k6-t1-fixture-room-1' and participation.user_id = room.host_user_id"))
			.isZero();
	}

	@Test
	void rooms_SQL의_마지막_SELECT는_호스트와_참가자_credential_fixture를_만든다() {
		String output = runRooms("t2-fixture", 2, 7, 2);

		assertThat(countOccurrences(output, "\"label\"")).isEqualTo(14);
		assertThat(output).contains("\"label\": \"room-1-host\"");
		assertThat(output).contains("\"label\": \"room-1-participant-1\"");
		assertThat(output).contains("\"label\": \"room-2-host\"");
		assertThat(output).contains("\"password\": \"" + PASSWORD + "\"");
		assertThat(output).contains("\"roomIds\": [");
		assertThat(output).contains("\"expectedMessageIds\": [");
	}

	@Test
	void 범위를_벗어난_규모와_형식이_틀린_run_id는_데이터를_만들지_않고_중단한다() {
		runRooms("t3-valid", 1, 7, 2);

		assertThatThrownBy(() -> runRooms("invalid%run", 1, 7, 2))
			.hasMessageContaining("rooms.sql failed");
		assertThatThrownBy(() -> runRooms("t3-invalid-size", 1, 7, 1))
			.hasMessageContaining("rooms.sql failed");
		assertThatThrownBy(() -> cleanup("invalid%run"))
			.hasMessageContaining("cleanup.sql");
		assertThat(count("select count(*) from rooms where title like 'k6-invalid%run-room-%'")).isZero();
		assertThat(count("select count(*) from rooms where title like 'k6-t3-invalid-size-room-%'")).isZero();
		assertThat(count("select count(*) from rooms where title = 'k6-t3-valid-room-1'")).isEqualTo(1);
	}

	@Test
	void 한_run_id의_cleanup은_LIKE_메타문자가_있는_다른_fixture를_지우지_않는다() {
		runRooms("t4-isolated_a", 1, 7, 2);
		runRooms("t4-isolatedxa", 1, 7, 2);

		cleanup("t4-isolated_a");

		assertThat(count("select count(*) from rooms where title = 'k6-t4-isolated_a-room-1'")).isZero();
		assertThat(count("select count(*) from rooms where title = 'k6-t4-isolatedxa-room-1'")).isEqualTo(1);
	}

	@Test
	void cleanup_SQL은_같은_제목의_일반_방을_지우지_않는다() {
		runRooms("t6-title-collision", 1, 7, 2);
		long fixtureRoomId = jdbcTemplate.queryForObject(
			"select id from rooms where title = 'k6-t6-title-collision-room-1'", Long.class);
		long ordinaryUserId = jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values ('ordinary.same-title@example.com', 'hash', 'ordinary', current_timestamp, current_timestamp) "
				+ "returning id",
			Long.class);
		long ordinaryRoomId = jdbcTemplate.queryForObject(
			"insert into rooms (game_id, host_user_id, room_type, title, description, experience_level, "
				+ "is_rulemaster_led, capacity, active_participant_count, start_at, place, status, version, "
				+ "created_at, updated_at) values (null, ?, 'PERSON_FOCUSED', 'k6-t6-title-collision-room-1', "
				+ "'ordinary room', 'ALL_LEVELS', false, 10, 0, current_timestamp + interval '30 days', 'test', "
				+ "'RECRUITING', 0, current_timestamp, current_timestamp) returning id",
			Long.class, ordinaryUserId);

		try {
			cleanup("t6-title-collision");

			assertThat(count("select count(*) from rooms where id = " + fixtureRoomId)).isZero();
			assertThat(count("select count(*) from rooms where id = " + ordinaryRoomId)).isEqualTo(1);
			assertThat(count("select count(*) from rooms where title = 'k6-t6-title-collision-room-1'"))
				.isEqualTo(1);
			assertThat(count("select count(*) from users where id = " + ordinaryUserId)).isEqualTo(1);
			assertThat(count("select count(*) from chat_k6_fixture_registry "
				+ "where run_id = 't6-title-collision'")).isZero();
		} finally {
			jdbcTemplate.update("delete from rooms where id = ?", ordinaryRoomId);
			jdbcTemplate.update("delete from users where id = ?", ordinaryUserId);
		}
	}

	@Test
	void cleanup_SQL은_같은_run_id의_방_계정과_파생_행을_지운다() {
		runRooms("t5-derived", 1, 7, 2);
		long roomId = jdbcTemplate.queryForObject(
			"select id from rooms where title = 'k6-t5-derived-room-1'", Long.class);
		long participantId = jdbcTemplate.queryForObject(
			"select id from users where email = 'k6.t5-derived.chat.r1.u2@example.com'", Long.class);
		long eventId = jdbcTemplate.queryForObject(
			"insert into notification_outbox_events (event_type, room_id, occurred_at, recorded_at, status, available_at) "
				+ "values ('PARTICIPATION_JOINED', ?, current_timestamp, current_timestamp, 'PENDING', current_timestamp) returning id",
			Long.class, roomId);
		jdbcTemplate.update(
			"insert into notification_outbox_recipients (outbox_event_id, recipient_user_id) values (?, ?)", eventId,
			participantId);
		jdbcTemplate.update(
			"insert into notifications (source_event_id, recipient_user_id, room_id, type, created_at, recorded_at, expires_at) "
				+ "values (?, ?, ?, 'PARTICIPANT_JOINED', current_timestamp, current_timestamp, current_timestamp + interval '90 days')",
			eventId, participantId, roomId);
		jdbcTemplate.update(
			"insert into room_waitlists (room_id, user_id, status, queue_order, queued_at, created_at, updated_at) "
				+ "values (?, ?, 'WAITING', 1, current_timestamp, current_timestamp, current_timestamp)",
			roomId, participantId);

		cleanup("t5-derived");

		assertThat(count("select count(*) from rooms where title = 'k6-t5-derived-room-1'")).isZero();
		assertThat(count("select count(*) from users where email like 'k6.t5-derived.chat.%@example.com'")).isZero();
		assertThat(count("select count(*) from chat_rooms where room_id = " + roomId)).isZero();
		assertThat(count("select count(*) from chat_messages where chat_room_id not in (select id from chat_rooms) "))
			.isZero();
		assertThat(count("select count(*) from notification_outbox_events where id = " + eventId)).isZero();
		assertThat(count("select count(*) from notification_outbox_recipients where outbox_event_id = " + eventId))
			.isZero();
		assertThat(count("select count(*) from notifications where source_event_id = " + eventId)).isZero();
		assertThat(count("select count(*) from room_waitlists where room_id = " + roomId)).isZero();
		assertThat(count("select count(*) from participations where room_id = " + roomId)).isZero();
	}

	@Test
	void 같은_run_id를_다른_규모로_재시드하면_기존_fixture를_조용히_변경하지_않고_중단한다() {
		runRooms("t7-rerun", 1, 7, 2);

		assertThatThrownBy(() -> runRooms("t7-rerun", 1, 9, 2))
			.hasMessageContaining("rooms.sql failed")
			.hasMessageContaining("fixture run t7-rerun already exists with different seed parameters");
		assertThat(count("select count(*) from rooms where title = 'k6-t7-rerun-room-1' "
			+ "and active_participant_count = 6")).isEqualTo(1);
		assertThat(count("select count(*) from participations participation join rooms room "
			+ "on room.id = participation.room_id where room.title = 'k6-t7-rerun-room-1' "
			+ "and participation.status = 'ACTIVE'")).isEqualTo(6);
	}

	private String runRooms(String runId, int roomCount, int accountsPerRoom, int messagesPerRoom) {
		ExecResult result = psql(
			"-v", "run_id=" + runId,
			"-v", "room_count=" + roomCount,
			"-v", "accounts_per_room=" + accountsPerRoom,
			"-v", "messages_per_room=" + messagesPerRoom,
			"-v", "password_hash=" + PASSWORD_HASH,
			"-v", "password=" + PASSWORD,
			"-f", "/tmp/rooms.sql");
		if (result.getExitCode() != 0) {
			throw new AssertionError("rooms.sql failed: " + result.getStderr());
		}
		return result.getStdout().trim();
	}

	private void cleanup(String runId) {
		ExecResult result = psql("-v", "run_id=" + runId, "-f", "/tmp/cleanup.sql");
		assertThat(result.getExitCode()).as("cleanup.sql: %s", result.getStderr()).isZero();
	}

	private ExecResult psql(String... arguments) {
		String[] command = new String[arguments.length + 11];
		command[0] = "psql";
		command[1] = "-X";
		command[2] = "-q";
		command[3] = "-A";
		command[4] = "-t";
		command[5] = "-U";
		command[6] = POSTGRES_USER;
		command[7] = "-d";
		command[8] = POSTGRES_DATABASE;
		command[9] = "-v";
		command[10] = "ON_ERROR_STOP=1";
		System.arraycopy(arguments, 0, command, 11, arguments.length);
		try {
			return postgres.execInContainer(command);
		} catch (Exception exception) {
			throw new AssertionError("fixture psql could not run", exception);
		}
	}

	private long count(String sql) {
		return jdbcTemplate.queryForObject(sql, Long.class);
	}

	private int countOccurrences(String value, String needle) {
		int count = 0;
		int index = 0;
		while ((index = value.indexOf(needle, index)) >= 0) {
			count++;
			index += needle.length();
		}
		return count;
	}
}
