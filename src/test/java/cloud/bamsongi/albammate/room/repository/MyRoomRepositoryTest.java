package cloud.bamsongi.albammate.room.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;

@SpringBootTest
@Transactional
class MyRoomRepositoryTest {

	private static final Instant BASE_TIME = Instant.parse("2026-07-28T00:00:00Z");

	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private ParticipationRepository participationRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Long currentUserId;
	private Long otherUserId;

	@BeforeEach
	void setUp() {
		currentUserId = insertUser("my-room-user@example.com", "나");
		otherUserId = insertUser("my-room-host@example.com", "다른 방장");
	}

	@Test
	void joined는_ACTIVE_관계와_CANCELED가_아닌_방만_FINISHED를_포함해_내림차순으로_반환한다() {
		Room finished = room(otherUserId, "완료된 참가 방", 500);
		Room canceledRoom = room(otherUserId, "취소된 참가 방", 400);
		Room canceledParticipation = room(otherUserId, "취소한 참가 방", 300);
		roomRepository.saveAllAndFlush(List.of(finished, canceledRoom, canceledParticipation));
		participate(finished, ParticipationStatus.ACTIVE);
		participate(canceledRoom, ParticipationStatus.ACTIVE);
		participate(canceledParticipation, ParticipationStatus.CANCELED);
		setRoomStatus(finished, RoomStatus.FINISHED);
		setRoomStatus(canceledRoom, RoomStatus.CANCELED);

		List<String> titles = roomRepository
			.findMyRooms(currentUserId, false, true, pageable())
			.map(Room::getTitle)
			.getContent();

		assertEquals(List.of("완료된 참가 방"), titles);
	}

	@Test
	void hosted는_취소된_방도_포함하고_all은_참가_방과_중복없이_합친다() {
		Room hostedCanceled = room(currentUserId, "취소된 내 방", 200);
		Room hostedRecruiting = room(currentUserId, "모집중 내 방", 100);
		Room joinedFinished = room(otherUserId, "완료된 참가 방", 300);
		roomRepository.saveAllAndFlush(List.of(hostedCanceled, hostedRecruiting, joinedFinished));
		participate(joinedFinished, ParticipationStatus.ACTIVE);
		setRoomStatus(hostedCanceled, RoomStatus.CANCELED);
		setRoomStatus(joinedFinished, RoomStatus.FINISHED);

		List<String> hostedTitles = roomRepository
			.findMyRooms(currentUserId, true, false, pageable())
			.map(Room::getTitle)
			.getContent();
		List<String> allTitles = roomRepository
			.findMyRooms(currentUserId, true, true, pageable())
			.map(Room::getTitle)
			.getContent();

		assertEquals(List.of("취소된 내 방", "모집중 내 방"), hostedTitles);
		assertEquals(List.of("완료된 참가 방", "취소된 내 방", "모집중 내 방"), allTitles);
	}

	private Long insertUser(String email, String nickname) {
		jdbcTemplate.update(
			"""
				insert into users (email, password_hash, nickname, created_at, updated_at)
				values (?, 'hash', ?, ?, ?)
				""",
			email,
			nickname,
			BASE_TIME,
			BASE_TIME);
		return jdbcTemplate.queryForObject(
			"select id from users where email = ?", Long.class, email);
	}

	private Room room(Long hostUserId, String title, long startsAtOffsetSeconds) {
		return Room.create(
			hostUserId,
			RoomType.PERSON_FOCUSED,
			title,
			null,
			null,
			ExperienceLevel.ALL_LEVELS,
			false,
			BASE_TIME.plusSeconds(startsAtOffsetSeconds),
			"테스트 장소",
			3);
	}

	private void participate(Room room, ParticipationStatus status) {
		Participation participation = Participation.createActive(room, currentUserId, BASE_TIME.plusSeconds(1));
		participationRepository.saveAndFlush(participation);
		if (status == ParticipationStatus.CANCELED) {
			jdbcTemplate.update(
				"""
					update participations
					set status = 'CANCELED', canceled_at = ?, updated_at = ?
					where id = ?
					""",
				BASE_TIME.plusSeconds(2),
				BASE_TIME.plusSeconds(2),
				participation.getId());
		}
	}

	private void setRoomStatus(Room room, RoomStatus status) {
		jdbcTemplate.update(
			"update rooms set status = ? where id = ?", status.name(), room.getId());
	}

	private PageRequest pageable() {
		return PageRequest.of(0, 10, Sort.by(Sort.Order.desc("startAt"), Sort.Order.desc("id")));
	}
}
