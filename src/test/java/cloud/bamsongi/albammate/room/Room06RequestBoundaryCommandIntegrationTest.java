package cloud.bamsongi.albammate.room;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.RoomStatusResponse;
import cloud.bamsongi.albammate.room.dto.RoomUpdateRequest;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationCancelService;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;
import cloud.bamsongi.albammate.room.service.command.RoomStatusChangeService;
import cloud.bamsongi.albammate.room.service.command.RoomUpdateService;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;

@SpringBootTest
@Import(Room06RequestBoundaryCommandIntegrationTest.FixedClockConfiguration.class)
class Room06RequestBoundaryCommandIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

	@Autowired
	private RoomUpdateService roomUpdateService;
	@Autowired
	private RoomStatusChangeService roomStatusChangeService;
	@Autowired
	private RoomParticipationService roomParticipationService;
	@Autowired
	private RoomParticipationCancelService roomParticipationCancelService;
	@Autowired
	private ParticipationRepository participationRepository;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private UserRepository userRepository;

	private final List<Long> participationIds = new ArrayList<>();
	private final List<Long> roomIds = new ArrayList<>();
	private final List<Long> userIds = new ArrayList<>();

	@AfterEach
	void tearDown() {
		participationIds.forEach(participationRepository::deleteById);
		roomIds.forEach(roomRepository::deleteById);
		userIds.forEach(userRepository::deleteById);
	}

	@Test
	void 수정은_시작_경계에서_상태_변경과_요청_변경을_함께_롤백한다() {
		User host = saveUser("수정 호스트");
		Room room = saveRoom(host, NOW, "수정 전 제목");
		RoomUpdateRequest request = new RoomUpdateRequest();
		request.setTitle("수정 시도 제목");

		assertError(
			ErrorCode.INVALID_ROOM_STATUS_TRANSITION,
			() -> roomUpdateService.updateRoom(host.getId(), room.getId(), request));

		Room stored = findRoom(room);
		assertEquals("수정 전 제목", stored.getTitle());
		assertEquals(RoomStatus.RECRUITING, stored.getStatus());
	}

	@Test
	void 취소_실패는_상태_변경을_롤백하고_종료는_같은_경계에서_FINISHED를_커밋한다() {
		User host = saveUser("상태 변경 호스트");
		Room room = saveRoom(host, NOW.minus(Room.AUTOMATIC_FINISH_AFTER_START), "자동 종료 경계 방");

		assertError(
			ErrorCode.INVALID_ROOM_STATUS_TRANSITION,
			() -> roomStatusChangeService.cancelRoom(host.getId(), room.getId()));
		assertEquals(RoomStatus.RECRUITING, findRoom(room).getStatus());

		RoomStatusResponse response = roomStatusChangeService.finishRoom(host.getId(), room.getId());

		assertEquals(RoomStatus.FINISHED, response.roomStatus());
		assertEquals(RoomStatus.FINISHED, findRoom(room).getStatus());
	}

	@Test
	void 참가는_시작_경계에서_참가_행과_카운터_변경을_함께_롤백한다() {
		User host = saveUser("참가 호스트");
		User participant = saveUser("참가 사용자");
		Room room = saveRoom(host, NOW, "참가 시작 경계 방");

		assertError(
			ErrorCode.ROOM_NOT_RECRUITING,
			() -> roomParticipationService.participate(participant.getId(), room.getId()));

		assertFalse(
			participationRepository
				.findByRoomIdAndUserId(room.getId(), participant.getId())
				.isPresent());
		Room stored = findRoom(room);
		assertEquals(0, stored.getActiveParticipantCount());
		assertEquals(RoomStatus.RECRUITING, stored.getStatus());
	}

	@Test
	void 참가_취소는_시작_경계에서_ACTIVE_관계와_카운터를_유지한다() {
		User host = saveUser("참가 취소 호스트");
		User participant = saveUser("참가 취소 사용자");
		Room room = saveRoom(host, NOW, "참가 취소 시작 경계 방");
		saveActiveParticipation(room, participant);

		assertError(
			ErrorCode.INVALID_ROOM_STATUS_TRANSITION,
			() -> roomParticipationCancelService.cancelParticipation(
				participant.getId(), room.getId()));

		assertEquals(
			ParticipationStatus.ACTIVE,
			participationRepository
				.findByRoomIdAndUserId(room.getId(), participant.getId())
				.orElseThrow()
				.getStatus());
		Room stored = findRoom(room);
		assertEquals(1, stored.getActiveParticipantCount());
		assertEquals(RoomStatus.RECRUITING, stored.getStatus());
	}

	private User saveUser(String nickname) {
		User user = userRepository.saveAndFlush(
			User.create(
				"room06-command-" + UUID.randomUUID() + "@example.com",
				"fixture-password-hash",
				nickname));
		userIds.add(user.getId());
		return user;
	}

	private Room saveRoom(User host, Instant startsAt, String title) {
		Room room = roomRepository.saveAndFlush(
			Room.create(
				host.getId(),
				RoomType.PERSON_FOCUSED,
				title,
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				startsAt,
				"홍대 테스트 장소",
				3));
		roomIds.add(room.getId());
		return room;
	}

	private void saveActiveParticipation(Room room, User participant) {
		room.addActiveParticipant();
		roomRepository.saveAndFlush(room);
		Participation participation = participationRepository.saveAndFlush(
			Participation.createActive(room, participant.getId(), NOW.minusSeconds(1)));
		participationIds.add(participation.getId());
	}

	private Room findRoom(Room room) {
		return roomRepository.findById(room.getId()).orElseThrow();
	}

	private void assertError(ErrorCode expected, Runnable action) {
		BusinessException exception = assertThrows(BusinessException.class, action::run);
		assertEquals(expected, exception.getErrorCode());
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
