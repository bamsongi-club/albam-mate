package cloud.bamsongi.albammate.room.service.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;

import cloud.bamsongi.albammate.room.dto.CreateRoomRequest;
import cloud.bamsongi.albammate.room.dto.ParticipantRoomResponse;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

@SpringBootTest
@Import(RoomCreateServiceTest.FixedClockConfiguration.class)
@Transactional
class RoomCreateServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");

	@Autowired
	private RoomCreateService roomCreateService;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@ParameterizedTest
	@ValueSource(strings = { "홍대", "강남", "건대", "잠실" })
	void T1_게임_중심_생성은_요청한_네_지역을_응답과_저장값에_보존한다(String region) throws Exception {
		Long hostUserId = insertUser("game-region-" + region + "@example.com", "방장");
		Long gameId = insertGame(2000L + region.hashCode());
		CreateRoomRequest request = requestFromJson(RoomType.GAME_FOCUSED, gameId, region, "게임 장소");

		ParticipantRoomResponse response = roomCreateService.createRoom(hostUserId, request);

		assertEquals(region, response.region());
		assertEquals(region, roomRepository.findById(response.id()).orElseThrow().getRegion());
	}

	@ParameterizedTest
	@ValueSource(strings = { "홍대", "강남", "건대", "잠실" })
	void T2_사람_중심_생성은_요청한_네_지역을_응답과_저장값에_보존한다(String region) throws Exception {
		Long hostUserId = insertUser("person-region-" + region + "@example.com", "방장");
		CreateRoomRequest request = requestFromJson(RoomType.PERSON_FOCUSED, null, region, "사람 장소");

		ParticipantRoomResponse response = roomCreateService.createRoom(hostUserId, request);

		assertEquals(region, response.region());
		assertEquals(region, roomRepository.findById(response.id()).orElseThrow().getRegion());
	}

	@ParameterizedTest
	@EnumSource(RoomType.class)
	void T3_두_모임_유형은_같은_선택_region을_응답과_저장값에_보존한다(RoomType roomType) throws Exception {
		Long hostUserId = insertUser("type-region-" + roomType + "@example.com", "방장");
		Long gameId = roomType == RoomType.GAME_FOCUSED ? insertGame(3000L) : null;
		CreateRoomRequest request = requestFromJson(roomType, gameId, "건대", "유형별 장소");

		ParticipantRoomResponse response = roomCreateService.createRoom(hostUserId, request);

		assertEquals("건대", response.region());
		assertEquals("건대", roomRepository.findById(response.id()).orElseThrow().getRegion());
	}

	@Test
	void T6_region과_place는_서로_독립적으로_응답과_저장값에_보존된다() throws Exception {
		Long hostUserId = insertUser("region-place@example.com", "방장");
		CreateRoomRequest request = requestFromJson(RoomType.PERSON_FOCUSED, null, "잠실", "홍대 장소");

		ParticipantRoomResponse response = roomCreateService.createRoom(hostUserId, request);

		assertEquals("잠실", response.region());
		assertEquals("홍대 장소", response.place());
		assertEquals("잠실", roomRepository.findById(response.id()).orElseThrow().getRegion());
		assertEquals("홍대 장소", roomRepository.findById(response.id()).orElseThrow().getPlace());
	}

	@Test
	void 실제_User_Game_Room을_저장하고_주최자_참가행은_만들지_않는다() {
		Long hostUserId = insertUser("host@example.com", "방장");
		Long gameId = insertGame(1001L);
		CreateRoomRequest request = new CreateRoomRequest(
			RoomType.GAME_FOCUSED,
			"  토요일 모임  ",
			"소개",
			gameId,
			ExperienceLevel.BEGINNER_WELCOME,
			true,
			NOW.plusSeconds(7200),
			"홍대",
			"  홍대 카페  ",
			4);

		ParticipantRoomResponse response = roomCreateService.createRoom(hostUserId, request);

		assertNotNull(response.id());
		assertEquals("토요일 모임", response.title());
		assertEquals("홍대 카페", response.place());
		assertEquals("홍대", response.region());
		assertNotNull(response.game());
		assertEquals(gameId, response.game().id());
		assertEquals(1001L, response.game().bggId());
		assertEquals("테스트 게임", response.game().name());
		assertEquals(RoomStatus.RECRUITING, response.status());
		assertEquals(
			0,
			roomRepository.findById(response.id()).orElseThrow().getActiveParticipantCount());
		assertEquals(
			hostUserId, roomRepository.findById(response.id()).orElseThrow().getHostUserId());
		assertEquals(
			NOW.plusSeconds(7200),
			roomRepository.findById(response.id()).orElseThrow().getStartAt());
		assertEquals("방장", response.host().nickname());
		assertEquals(1, response.participants().size());
		assertFalse(response.joinable());
		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"select count(*) from participations where room_id = ?",
				Integer.class,
				response.id()));
	}

	@Test
	void T4_주최자에게_프로필_이미지가_있으면_생성_응답_host에_현재_프로필_이미지_URL이_채워진다() {
		Long hostUserId = insertUser("host-with-image@example.com", "방장", "https://cdn.example.com/host.png");
		CreateRoomRequest request = new CreateRoomRequest(
			RoomType.PERSON_FOCUSED,
			"프로필 이미지 방",
			null,
			null,
			ExperienceLevel.ALL_LEVELS,
			true,
			NOW.plusSeconds(7200),
			"홍대",
			"홍대",
			3);

		ParticipantRoomResponse response = roomCreateService.createRoom(hostUserId, request);

		assertEquals("https://cdn.example.com/host.png", response.host().profileImageUrl());
	}

	@Test
	void T6_주최자에게_프로필_이미지가_없으면_생성_응답_host_profileImageUrl은_null이다() {
		Long hostUserId = insertUser("host-without-image@example.com", "방장");
		CreateRoomRequest request = new CreateRoomRequest(
			RoomType.PERSON_FOCUSED,
			"프로필 이미지 없는 방",
			null,
			null,
			ExperienceLevel.ALL_LEVELS,
			true,
			NOW.plusSeconds(7200),
			"홍대",
			"홍대",
			3);

		ParticipantRoomResponse response = roomCreateService.createRoom(hostUserId, request);

		assertEquals(null, response.host().profileImageUrl());
	}

	private Long insertUser(String email, String nickname) {
		jdbcTemplate.update(
			"insert into users "
				+ "(email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'fixture-password-hash', ?, "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-27T00:00:00Z', "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-27T00:00:00Z')",
			email,
			nickname);
		return jdbcTemplate.queryForObject(
			"select id from users where email = ?", Long.class, email);
	}

	private Long insertUser(String email, String nickname, String profileImageUrl) {
		jdbcTemplate.update(
			"insert into users "
				+ "(email, password_hash, nickname, profile_image_url, created_at, updated_at) "
				+ "values (?, 'fixture-password-hash', ?, ?, "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-27T00:00:00Z', "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-27T00:00:00Z')",
			email,
			nickname,
			profileImageUrl);
		return jdbcTemplate.queryForObject(
			"select id from users where email = ?", Long.class, email);
	}

	private Long insertGame(long bggId) {
		jdbcTemplate.update(
			"insert into games "
				+ "(bgg_id, name, english_name, supported_player_count, tag, "
				+ "estimated_play_time, description, detail_description, created_at, updated_at) "
				+ "values (?, '테스트 게임', 'Test Game', '2~4명', '전략', '60~90분', "
				+ "'게임 설명', '게임 상세 설명', "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-27T00:00:00Z', "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-27T00:00:00Z')",
			bggId);
		return jdbcTemplate.queryForObject(
			"select id from games where bgg_id = ?", Long.class, bggId);
	}

	private CreateRoomRequest requestFromJson(
		RoomType roomType, Long gameId, String region, String place) throws Exception {
		ObjectMapper objectMapper = new ObjectMapper()
			.findAndRegisterModules()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		return objectMapper.readValue(
			"""
				{
				  "roomType": "%s",
				  "title": "지역 테스트 방",
				  "description": null,
				  "gameId": %s,
				  "experienceLevel": "ALL_LEVELS",
				  "isRulemasterLed": false,
				  "startsAt": "2026-07-27T02:00:00Z",
				  "region": "%s",
				  "place": "%s",
				  "recruitmentCapacity": 3
				}
				""".formatted(roomType, gameId == null ? "null" : gameId, region, place),
			CreateRoomRequest.class);
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
