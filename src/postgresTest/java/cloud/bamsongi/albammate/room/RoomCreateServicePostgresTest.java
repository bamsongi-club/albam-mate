package cloud.bamsongi.albammate.room;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.room.dto.CreateRoomRequest;
import cloud.bamsongi.albammate.room.dto.ParticipantRoomResponse;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.command.RoomCreateService;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

@Testcontainers
@SpringBootTest(properties = {
	"spring.task.scheduling.enabled=false",
	"app.notification.relay.enabled=false",
	"app.chat.retention.enabled=false"})
@Import(RoomCreateServicePostgresTest.FixedClockConfiguration.class)
@Transactional
class RoomCreateServicePostgresTest extends SharedPostgresIntegrationSupport {

	private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

	@Autowired
	private RoomCreateService roomCreateService;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@ParameterizedTest
	@EnumSource(RoomType.class)
	void T1_T2_직접_생성은_PostgreSQL에_선택_region을_응답과_저장값으로_보존한다(RoomType roomType) {
		Long hostUserId = insertUser("postgres-region-" + roomType + "@example.com");
		Long gameId = roomType == RoomType.GAME_FOCUSED ? insertGame() : null;
		String region = roomType == RoomType.GAME_FOCUSED ? "강남" : "잠실";
		CreateRoomRequest request = new CreateRoomRequest(
			roomType,
			"PostgreSQL 지역 방",
			null,
			gameId,
			ExperienceLevel.ALL_LEVELS,
			false,
			NOW.plusSeconds(3600),
			region,
			"PostgreSQL 장소",
			3);

		ParticipantRoomResponse response = roomCreateService.createRoom(hostUserId, request);

		assertEquals(region, response.region());
		assertEquals(region, roomRepository.findById(response.id()).orElseThrow().getRegion());
	}

	private Long insertUser(String email) {
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'postgres-test-hash', 'PostgreSQL 방장', ?, ?)",
			email,
			Timestamp.from(NOW),
			Timestamp.from(NOW));
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	private Long insertGame() {
		jdbcTemplate.update(
			"insert into games (bgg_id, name, english_name, supported_player_count, tag, "
				+ "estimated_play_time, description, detail_description, created_at, updated_at) "
				+ "values (1077, 'PostgreSQL 테스트 게임', 'PostgreSQL Test Game', '2~4명', '전략', "
				+ "'60~90분', '설명', '상세 설명', ?, ?)",
			Timestamp.from(NOW),
			Timestamp.from(NOW));
		return jdbcTemplate.queryForObject("select id from games where bgg_id = 1077", Long.class);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfiguration {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}
}
