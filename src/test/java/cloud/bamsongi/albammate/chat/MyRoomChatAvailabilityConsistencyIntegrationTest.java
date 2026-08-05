package cloud.bamsongi.albammate.chat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.chat.service.ChatMessageHistoryQueryService;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.MyRoomListItem;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.MyRoomRole;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.query.MyRoomQueryService;

/**
 * MyRoomListItem.chatAvailable 계산이 RoomChatAccessGuard 기반 실제 채팅 접근 결과와
 * CHAT-05 완료 기준 조합별로 일치하는지 검증한다.
 */
@SpringBootTest
@Import(MyRoomChatAvailabilityConsistencyIntegrationTest.FixedClockConfiguration.class)
class MyRoomChatAvailabilityConsistencyIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");
	private static final Instant RECRUITING_START = NOW.plusSeconds(3600);
	private static final Instant CLOSED_START = NOW.minusSeconds(1);
	private static final Instant FINISHED_START = NOW.minus(Room.AUTOMATIC_FINISH_AFTER_START);

	@Autowired
	private MyRoomQueryService myRoomQueryService;
	@Autowired
	private ChatMessageHistoryQueryService chatMessageHistoryQueryService;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private ParticipationRepository participationRepository;
	@Autowired
	private ChatRoomRepository chatRoomRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final List<Long> roomIds = new ArrayList<>();
	private final List<Long> participationIds = new ArrayList<>();
	private final List<Long> userIds = new ArrayList<>();

	@AfterEach
	void tearDown() {
		participationIds.forEach(participationRepository::deleteById);
		roomIds.forEach(roomId -> jdbcTemplate.update("delete from chat_rooms where room_id = ?", roomId));
		roomIds.forEach(roomId -> jdbcTemplate.update("delete from rooms where id = ?", roomId));
		userIds.forEach(userId -> jdbcTemplate.update("delete from users where id = ?", userId));
	}

	@Test
	void 방_생성자는_생성_직후_내_모임에서_chatAvailable_true를_받고_직접_접근도_허용된다() {
		long hostUserId = insertUser("주최자");
		Room room = saveRoom(hostUserId, RECRUITING_START);

		assertConsistent(hostUserId, room.getId(), MyRoomRole.HOSTED, true);
	}

	@Test
	void 현재_ACTIVE_참가자는_RECRUITING_CLOSED_방에서_chatAvailable_true를_받고_직접_접근도_허용된다() {
		for (Instant startAt : List.of(RECRUITING_START, CLOSED_START)) {
			long hostUserId = insertUser("주최자");
			long participantUserId = insertUser("참가자");
			Room room = saveRoom(hostUserId, startAt);
			addActiveParticipation(room, participantUserId);

			assertConsistent(participantUserId, room.getId(), MyRoomRole.JOINED, true);
		}
	}

	@Test
	void 참가를_취소한_사용자와_CANCELED_FINISHED_방은_chatAvailable_false이고_직접_접근도_거절된다() {
		long hostUserId = insertUser("주최자");
		long participantUserId = insertUser("참가자");
		Room activeRoom = saveRoom(hostUserId, RECRUITING_START);
		Participation participation = addActiveParticipation(activeRoom, participantUserId);
		participation.cancel(NOW.plusSeconds(1));
		participationRepository.saveAndFlush(participation);

		assertRoomAbsentFromMyRooms(participantUserId, MyRoomRole.JOINED, activeRoom.getId());
		assertChatAccessForbidden(participantUserId, activeRoom.getId());

		long canceledHostUserId = insertUser("주최자2");
		Room canceledRoom = saveRoom(canceledHostUserId, RECRUITING_START);
		canceledRoom.cancel();
		roomRepository.saveAndFlush(canceledRoom);
		assertConsistent(canceledHostUserId, canceledRoom.getId(), MyRoomRole.HOSTED, false);

		long finishedHostUserId = insertUser("주최자3");
		Room finishedRoom = saveRoom(finishedHostUserId, FINISHED_START);
		assertConsistent(finishedHostUserId, finishedRoom.getId(), MyRoomRole.HOSTED, false);
	}

	@ParameterizedTest
	@EnumSource(value = RoomStatus.class, names = { "RECRUITING", "CLOSED" })
	void RECRUITING_CLOSED_상태와_관계_조합별로_내_모임_표시와_직접_접근_결과가_일치한다(RoomStatus status) {
		Instant startAt = status == RoomStatus.RECRUITING ? RECRUITING_START : CLOSED_START;

		long hostUserId = insertUser("주최자");
		Room hostRoom = saveRoom(hostUserId, startAt);
		assertConsistent(hostUserId, hostRoom.getId(), MyRoomRole.HOSTED, true);

		long activeHostUserId = insertUser("주최자2");
		long activeParticipantUserId = insertUser("활성참가자");
		Room activeRoom = saveRoom(activeHostUserId, startAt);
		addActiveParticipation(activeRoom, activeParticipantUserId);
		assertConsistent(activeParticipantUserId, activeRoom.getId(), MyRoomRole.JOINED, true);

		long canceledParticipationHostUserId = insertUser("주최자3");
		long canceledParticipantUserId = insertUser("취소참가자");
		Room canceledParticipationRoom = saveRoom(canceledParticipationHostUserId, startAt);
		Participation canceledParticipation = addActiveParticipation(
			canceledParticipationRoom, canceledParticipantUserId);
		canceledParticipation.cancel(NOW.plusSeconds(1));
		participationRepository.saveAndFlush(canceledParticipation);
		assertRoomAbsentFromMyRooms(
			canceledParticipantUserId, MyRoomRole.JOINED, canceledParticipationRoom.getId());
		assertChatAccessForbidden(canceledParticipantUserId, canceledParticipationRoom.getId());

		long nonParticipantHostUserId = insertUser("주최자4");
		long nonParticipantUserId = insertUser("비참가자");
		Room nonParticipantRoom = saveRoom(nonParticipantHostUserId, startAt);
		assertRoomAbsentFromMyRooms(nonParticipantUserId, MyRoomRole.JOINED, nonParticipantRoom.getId());
		assertChatAccessForbidden(nonParticipantUserId, nonParticipantRoom.getId());
	}

	private void assertConsistent(long userId, long roomId, MyRoomRole role, boolean expectedAvailable) {
		MyRoomListItem item = findMyRoomItem(userId, role, roomId);
		assertEquals(expectedAvailable, item.chatAvailable());
		if (expectedAvailable) {
			assertDoesNotThrow(() -> chatMessageHistoryQueryService.history(userId, roomId, null, 50));
		} else {
			assertChatAccessForbidden(userId, roomId);
		}
	}

	private void assertRoomAbsentFromMyRooms(long userId, MyRoomRole role, long roomId) {
		boolean present = myRoomQueryService.findPage(userId, role, 0, 10).content().stream()
			.anyMatch(item -> item.id().equals(roomId));
		assertFalse(present, "참가 취소·비참가자 방은 내 모임 목록에 나타나면 안 됩니다.");
	}

	private void assertChatAccessForbidden(long userId, long roomId) {
		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> chatMessageHistoryQueryService.history(userId, roomId, null, 50));
		assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
	}

	private MyRoomListItem findMyRoomItem(long userId, MyRoomRole role, long roomId) {
		return myRoomQueryService.findPage(userId, role, 0, 10).content().stream()
			.filter(item -> item.id().equals(roomId))
			.findFirst()
			.orElseThrow(() -> new AssertionError("내 모임 목록에 방이 없습니다: " + roomId));
	}

	private Participation addActiveParticipation(Room room, long userId) {
		Participation participation = participationRepository
			.saveAndFlush(Participation.createActive(room, userId, NOW));
		participationIds.add(participation.getId());
		return participation;
	}

	private Room saveRoom(long hostUserId, Instant startAt) {
		Room room = roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"채팅 진입 일치성 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				startAt,
				"홍대",
				3));
		roomIds.add(room.getId());
		chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));
		return room;
	}

	private long insertUser(String nickname) {
		String email = "chat-availability-" + UUID.randomUUID() + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?)",
			email,
			nickname,
			NOW,
			NOW);
		Long userId = jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
		userIds.add(userId);
		return userId;
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
