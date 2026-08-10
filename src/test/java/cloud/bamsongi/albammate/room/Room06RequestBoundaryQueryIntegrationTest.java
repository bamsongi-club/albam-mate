package cloud.bamsongi.albammate.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.response.PageResponse;
import cloud.bamsongi.albammate.room.dto.MyRoomListItem;
import cloud.bamsongi.albammate.room.dto.PublicRoomResponse;
import cloud.bamsongi.albammate.room.dto.RoomDetailResponse;
import cloud.bamsongi.albammate.room.dto.RoomListRequest;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.MyRoomRole;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.query.MyRoomQueryService;
import cloud.bamsongi.albammate.room.service.query.RoomDetailService;
import cloud.bamsongi.albammate.room.service.query.RoomListQueryService;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;

@SpringBootTest
@Import(Room06RequestBoundaryQueryIntegrationTest.FixedClockConfiguration.class)
class Room06RequestBoundaryQueryIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

	@Autowired
	private RoomListQueryService roomListQueryService;
	@Autowired
	private RoomDetailService roomDetailService;
	@Autowired
	private MyRoomQueryService myRoomQueryService;
	@Autowired
	private ParticipationRepository participationRepository;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private ChatRoomRepository chatRoomRepository;
	@Autowired
	private UserRepository userRepository;

	private final List<Long> participationIds = new ArrayList<>();
	private final List<Long> roomIds = new ArrayList<>();
	private final List<Long> userIds = new ArrayList<>();

	@AfterEach
	void tearDown() {
		participationIds.forEach(participationRepository::deleteById);
		roomIds.stream()
			.map(chatRoomRepository::findByRoomId)
			.flatMap(Optional::stream)
			.forEach(chatRoomRepository::delete);
		roomIds.forEach(roomRepository::deleteById);
		userIds.forEach(userRepository::deleteById);
	}

	@Test
	void 목록은_시작_경계부터_CLOSED_유효_상태로_페이지를_계산하고_저장_상태를_변경하지_않는다() {
		User host = saveUser("목록 호스트");
		Room closedAtStart = saveRoom(host, NOW, "시작 경계 방");
		Room futureRoom = saveRoom(host, NOW.plusSeconds(1), "미래 방");

		PageResponse<PublicRoomResponse> response = roomListQueryService.findPage(
			RoomType.PERSON_FOCUSED, null, null, 0, 1, Optional.empty());

		assertEquals(1, response.content().size());
		assertEquals(closedAtStart.getId(), response.content().getFirst().id());
		assertEquals(RoomStatus.CLOSED, response.content().getFirst().status());
		assertFalse(response.content().getFirst().joinable());
		assertEquals(2, response.totalElements());
		assertEquals(2, response.totalPages());
		assertEquals(RoomStatus.RECRUITING, findRoom(closedAtStart).getStatus());
		assertEquals(RoomStatus.RECRUITING, findRoom(futureRoom).getStatus());
	}

	@Test
	void 목록은_자동_종료_경계부터_FINISHED_유효_상태를_제외하고_저장_상태를_변경하지_않는다() {
		User host = saveUser("목록 종료 호스트");
		Room automaticallyFinished = saveRoom(host, NOW.minus(Room.AUTOMATIC_FINISH_AFTER_START), "자동 종료 방");
		Room closedAtStart = saveRoom(host, NOW, "시작 경계 방");
		Room futureRoom = saveRoom(host, NOW.plusSeconds(1), "미래 방");

		PageResponse<PublicRoomResponse> response = roomListQueryService.findPage(
			RoomType.PERSON_FOCUSED, null, null, 0, 1, Optional.empty());

		assertEquals(1, response.content().size());
		assertEquals(closedAtStart.getId(), response.content().getFirst().id());
		assertEquals(2, response.totalElements());
		assertEquals(2, response.totalPages());
		assertEquals(RoomStatus.RECRUITING, findRoom(automaticallyFinished).getStatus());
		assertEquals(RoomStatus.RECRUITING, findRoom(closedAtStart).getStatus());
		assertEquals(RoomStatus.RECRUITING, findRoom(futureRoom).getStatus());
	}

	@Test
	void status_필터는_시작_경계의_유효_상태로_content_count_페이지와_응답을_계산한다() {
		User host = saveUser("시작 경계 상태 필터 호스트");
		Room closedAtStart = saveRoom(host, NOW, "시작 경계 방");
		Room firstRecruiting = saveRoom(host, NOW.plusSeconds(1), "첫 번째 모집 중 방");
		Room secondRecruiting = saveRoom(host, NOW.plusSeconds(2), "두 번째 모집 중 방");

		RoomListRequest closedRequest = roomListRequest(RoomStatus.CLOSED, 0, 1);
		RoomListRequest firstRecruitingRequest = roomListRequest(RoomStatus.RECRUITING, 0, 1);
		RoomListRequest secondRecruitingRequest = roomListRequest(RoomStatus.RECRUITING, 1, 1);

		PageResponse<PublicRoomResponse> closedResponse = roomListQueryService.findPage(closedRequest, Optional.empty());
		PageResponse<PublicRoomResponse> firstRecruitingResponse = roomListQueryService.findPage(
			firstRecruitingRequest, Optional.empty());
		PageResponse<PublicRoomResponse> secondRecruitingResponse = roomListQueryService.findPage(
			secondRecruitingRequest, Optional.empty());

		assertEquals(1, closedResponse.totalElements());
		assertEquals(closedAtStart.getId(), closedResponse.content().getFirst().id());
		assertEquals(RoomStatus.CLOSED, closedResponse.content().getFirst().status());
		assertEquals(2, firstRecruitingResponse.totalElements());
		assertEquals(2, firstRecruitingResponse.totalPages());
		assertEquals(firstRecruiting.getId(), firstRecruitingResponse.content().getFirst().id());
		assertEquals(RoomStatus.RECRUITING, firstRecruitingResponse.content().getFirst().status());
		assertEquals(secondRecruiting.getId(), secondRecruitingResponse.content().getFirst().id());
		assertEquals(RoomStatus.RECRUITING, findRoom(closedAtStart).getStatus());
	}

	@Test
	void status_필터는_종료_경계의_FINISHED_유효_상태를_제외하고_직전_CLOSED를_반환한다() {
		User host = saveUser("종료 경계 상태 필터 호스트");
		Room automaticallyFinished = saveRoom(host, NOW.minus(Room.AUTOMATIC_FINISH_AFTER_START), "종료 경계 방");
		Room closedBeforeFinish = saveRoom(
			host, NOW.minus(Room.AUTOMATIC_FINISH_AFTER_START).plusSeconds(1), "종료 직전 방");
		RoomListRequest closedRequest = roomListRequest(RoomStatus.CLOSED, 0, 10);

		PageResponse<PublicRoomResponse> response = roomListQueryService.findPage(closedRequest, Optional.empty());

		assertEquals(1, response.totalElements());
		assertEquals(closedBeforeFinish.getId(), response.content().getFirst().id());
		assertEquals(RoomStatus.CLOSED, response.content().getFirst().status());
		assertEquals(RoomStatus.RECRUITING, findRoom(automaticallyFinished).getStatus());
		assertEquals(RoomStatus.RECRUITING, findRoom(closedBeforeFinish).getStatus());
	}

	@Test
	void 상세는_시작_경계_방의_상태를_먼저_CLOSED로_변경해_반환한다() {
		User host = saveUser("상세 호스트");
		Room room = saveRoom(host, NOW, "상세 시작 경계 방");

		RoomDetailResponse response = roomDetailService.findRoomDetail(room.getId(), Optional.empty());

		PublicRoomResponse publicRoomResponse = assertInstanceOf(PublicRoomResponse.class, response);
		assertEquals(RoomStatus.CLOSED, publicRoomResponse.status());
		assertEquals(RoomStatus.CLOSED, findRoom(room).getStatus());
	}

	@Test
	void 최종_상태_상세_실패_뒤에도_현재_시각에_따른_상태_변경은_커밋된다() {
		User host = saveUser("최종 상태 호스트");
		Room room = saveRoom(host, NOW.minus(Room.AUTOMATIC_FINISH_AFTER_START), "최종 상태 방");

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> roomDetailService.findRoomDetail(room.getId(), Optional.empty()));

		assertEquals(ErrorCode.ROOM_NOT_FOUND, exception.getErrorCode());
		assertEquals(RoomStatus.FINISHED, findRoom(room).getStatus());
	}

	@Test
	void 내_모임은_자동_종료_경계의_방을_FINISHED로_반환하지만_저장_상태와_참여_이력은_유지한다() {
		User host = saveUser("내 모임 호스트");
		Room room = saveRoom(host, NOW.minus(Room.AUTOMATIC_FINISH_AFTER_START), "내 모임 종료 방");

		PageResponse<MyRoomListItem> response = myRoomQueryService.findPage(host.getId(), MyRoomRole.HOSTED, 0, 10);

		assertEquals(1, response.totalElements());
		assertEquals(room.getId(), response.content().getFirst().id());
		assertEquals(RoomStatus.FINISHED, response.content().getFirst().status());
		assertEquals(RoomStatus.RECRUITING, findRoom(room).getStatus());
	}

	private User saveUser(String nickname) {
		User user = userRepository.saveAndFlush(
			User.create(
				"room06-query-" + UUID.randomUUID() + "@example.com",
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

	private Room findRoom(Room room) {
		return roomRepository.findById(room.getId()).orElseThrow();
	}

	private RoomListRequest roomListRequest(RoomStatus status, int page, int size) {
		RoomListRequest request = new RoomListRequest();
		request.setType(RoomType.PERSON_FOCUSED);
		request.setStatus(status);
		request.setPage(page);
		request.setSize(size);
		return request;
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
