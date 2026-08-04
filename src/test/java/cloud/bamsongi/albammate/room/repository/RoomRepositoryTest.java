package cloud.bamsongi.albammate.room.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;

@SpringBootTest
@Transactional
class RoomRepositoryTest {

	private static final Instant BASE_TIME = Instant.parse("2026-07-28T00:00:00Z");
	private static final Set<RoomStatus> PUBLIC_STATUSES = Set.of(RoomStatus.RECRUITING, RoomStatus.CLOSED);

	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Long hostUserId;
	private Long gameId;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update(
			"""
				insert into users (email, password_hash, nickname, created_at, updated_at)
				values ('room-list-host@example.com', 'hash', '방장', ?, ?)
				""",
			BASE_TIME,
			BASE_TIME);
		hostUserId = jdbcTemplate.queryForObject(
			"select id from users where email = 'room-list-host@example.com'",
			Long.class);
		jdbcTemplate.update(
			"""
				insert into games (
				    bgg_id, name, english_name, supported_player_count, tag,
				    estimated_play_time, description, detail_description, created_at, updated_at)
				values (1001, '카탄', 'Catan', '3~4명', '전략', '60분', '설명', '상세 설명', ?, ?)
				""",
			BASE_TIME,
			BASE_TIME);
		gameId = jdbcTemplate.queryForObject("select id from games where bgg_id = 1001", Long.class);
	}

	@Test
	void 공개_상태와_게임_조건을_필터하고_시작시각과_ID_오름차순으로_페이지를_반환한다() {
		Room first = gameRoom("첫 게임방", BASE_TIME.plusSeconds(60));
		Room second = gameRoom("두 번째 게임방", BASE_TIME.plusSeconds(120));
		Room canceled = gameRoom("취소된 게임방", BASE_TIME.plusSeconds(30));
		roomRepository.saveAllAndFlush(List.of(second, canceled, first));
		jdbcTemplate.update("update rooms set status = 'CANCELED' where id = ?", canceled.getId());

		Page<Room> firstPage = roomRepository.findPublicRoomsWithoutKeyword(
			RoomType.GAME_FOCUSED,
			gameId,
			PUBLIC_STATUSES,
			PageRequest.of(
				0, 1, Sort.by(Sort.Order.asc("startAt"), Sort.Order.asc("id"))));
		Page<Room> secondPage = roomRepository.findPublicRoomsWithoutKeyword(
			RoomType.GAME_FOCUSED,
			gameId,
			PUBLIC_STATUSES,
			PageRequest.of(
				1, 1, Sort.by(Sort.Order.asc("startAt"), Sort.Order.asc("id"))));

		assertEquals(2, firstPage.getTotalElements());
		assertEquals(
			List.of("첫 게임방"), firstPage.getContent().stream().map(Room::getTitle).toList());
		assertEquals(
			List.of("두 번째 게임방"), secondPage.getContent().stream().map(Room::getTitle).toList());
	}

	@Test
	void 사람_중심_방은_제목을_대소문자_구분없이_부분검색한다() {
		roomRepository.saveAllAndFlush(
			List.of(
				personRoom("Party Night"),
				personRoom("스터디 모임"),
				gameRoom("Party가 아닌 게임 중심 방", BASE_TIME.plusSeconds(300))));

		Page<Room> result = roomRepository.findPublicRoomsByTitleContainingIgnoreCase(
			RoomType.PERSON_FOCUSED,
			null,
			"party",
			PUBLIC_STATUSES,
			PageRequest.of(
				0, 10, Sort.by(Sort.Order.asc("startAt"), Sort.Order.asc("id"))));

		assertEquals(
			List.of("Party Night"), result.getContent().stream().map(Room::getTitle).toList());
	}

	@Test
	void 사람_중심_검색은_PERCENT_UNDERSCORE_ESCAPE를_제목의_리터럴로_처리한다() {
		roomRepository.saveAllAndFlush(
			List.of(
				personRoom("100% 모임"),
				personRoom("100X 모임"),
				personRoom("A_B 모임"),
				personRoom("AXB 모임"),
				personRoom("느낌! 모임"),
				personRoom("느낌X 모임")));

		assertEquals(List.of("100% 모임"), findPersonRoomTitles("100!%"));
		assertEquals(List.of("A_B 모임"), findPersonRoomTitles("A!_B"));
		assertEquals(List.of("느낌! 모임"), findPersonRoomTitles("느낌!!"));
	}

	@Test
	void 기대한_version과_일치할_때만_ROOM_version을_claim한다() {
		Room room = personRoom("version claim 방");
		roomRepository.saveAndFlush(room);

		assertEquals(1, roomRepository.claimVersion(room.getId(), 0L));
		assertEquals(0, roomRepository.claimVersion(room.getId(), 0L));
		assertEquals(
			1L,
			jdbcTemplate.queryForObject("select version from rooms where id = ?", Long.class, room.getId()));
	}

	private List<String> findPersonRoomTitles(String keyword) {
		return roomRepository
			.findPublicRoomsByTitleContainingIgnoreCase(
				RoomType.PERSON_FOCUSED,
				null,
				keyword,
				PUBLIC_STATUSES,
				PageRequest.of(
					0, 10, Sort.by(Sort.Order.asc("startAt"), Sort.Order.asc("id"))))
			.getContent()
			.stream()
			.map(Room::getTitle)
			.toList();
	}

	private Room gameRoom(String title, Instant startsAt) {
		return Room.create(
			hostUserId,
			RoomType.GAME_FOCUSED,
			title,
			null,
			gameId,
			ExperienceLevel.ALL_LEVELS,
			false,
			startsAt,
			"테스트 장소",
			3);
	}

	private Room personRoom(String title) {
		return Room.create(
			hostUserId,
			RoomType.PERSON_FOCUSED,
			title,
			null,
			null,
			ExperienceLevel.ALL_LEVELS,
			false,
			BASE_TIME.plusSeconds(300),
			"테스트 장소",
			3);
	}
}
