package cloud.bamsongi.albammate.room.service.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

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

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfiguration {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}
}
