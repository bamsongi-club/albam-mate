package cloud.bamsongi.albammate.room.service.query;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

/**
 * {@link PublicRoomQuery}가 {@link RoomListSearchCriteria}의 9개 필드를 findPublicRoomsAt JPQL
 * 파라미터로 정확히 변환함을 mock으로 검증한다. JPQL을 실행하지 않으므로 대소문자 무시 부분검색, ESCAPE 리터럴
 * 처리, CANCELED 방 배제와 startAt 오름차순 페이지네이션은
 * {@link cloud.bamsongi.albammate.room.repository.RoomRepositoryTest}가 findPublicRoomsAt을 직접
 * 호출해 실DB로 검증한다. 단 RECRUITING·CLOSED 세부 상태 분기와 gameId 배제는 실DB 테스트가 없고 이 클래스의
 * 파라미터 변환 검증에만 의존한다.
 */
class PublicRoomQueryTest {

	private static final Instant REQUEST_TIME = Instant.parse("2026-07-28T00:00:00Z");
	private static final Set<RoomStatus> PUBLIC_STATUSES = Set.of(RoomStatus.RECRUITING, RoomStatus.CLOSED);
	private static final Set<ExperienceLevel> ALL_EXPERIENCE_LEVELS = Set.of(ExperienceLevel.values());

	private final RoomRepository roomRepository = mock(RoomRepository.class);
	private final PublicRoomQuery publicRoomQuery = new PublicRoomQuery(roomRepository);

	@Test
	void roomType_필드를_그대로_JPQL_roomType_파라미터로_전달한다() {
		Pageable pageable = pageable();
		RoomListSearchCriteria criteria = baseline(RoomType.PERSON_FOCUSED, null, null, null, null, null, Set.of(),
			false);

		publicRoomQuery.findPublicRooms(criteria, pageable, REQUEST_TIME);

		verify(roomRepository).findPublicRoomsAt(
			RoomType.PERSON_FOCUSED, false, false, false, REQUEST_TIME, null, false, "", false, Instant.EPOCH, false,
			Instant.EPOCH, false, 0, ALL_EXPERIENCE_LEVELS, false, PUBLIC_STATUSES,
			REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START), pageable);
	}

	@Test
	void status_필드가_RECRUITING이면_statusFilterEnabled와_recruitingStatusFilter만_켠다() {
		Pageable pageable = pageable();
		RoomListSearchCriteria criteria = baseline(null, RoomStatus.RECRUITING, null, null, null, null, Set.of(),
			false);

		publicRoomQuery.findPublicRooms(criteria, pageable, REQUEST_TIME);

		verify(roomRepository).findPublicRoomsAt(
			null, true, true, false, REQUEST_TIME, null, false, "", false, Instant.EPOCH, false, Instant.EPOCH,
			false, 0, ALL_EXPERIENCE_LEVELS, false, PUBLIC_STATUSES,
			REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START), pageable);
	}

	@Test
	void status_필드가_CLOSED이면_statusFilterEnabled와_closedStatusFilter만_켠다() {
		Pageable pageable = pageable();
		RoomListSearchCriteria criteria = baseline(null, RoomStatus.CLOSED, null, null, null, null, Set.of(), false);

		publicRoomQuery.findPublicRooms(criteria, pageable, REQUEST_TIME);

		verify(roomRepository).findPublicRoomsAt(
			null, true, false, true, REQUEST_TIME, null, false, "", false, Instant.EPOCH, false, Instant.EPOCH,
			false, 0, ALL_EXPERIENCE_LEVELS, false, PUBLIC_STATUSES,
			REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START), pageable);
	}

	@Test
	void gameId_필드를_그대로_JPQL_gameId_파라미터로_전달한다() {
		Pageable pageable = pageable();
		RoomListSearchCriteria criteria = baseline(null, null, 55L, null, null, null, Set.of(), false);

		publicRoomQuery.findPublicRooms(criteria, pageable, REQUEST_TIME);

		verify(roomRepository).findPublicRoomsAt(
			null, false, false, false, REQUEST_TIME, 55L, false, "", false, Instant.EPOCH, false, Instant.EPOCH,
			false, 0, ALL_EXPERIENCE_LEVELS, false, PUBLIC_STATUSES,
			REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START), pageable);
	}

	@Test
	void keyword_필드는_대소문자를_바꾸지_않고_키워드_필터를_켠_채로_전달한다() {
		Pageable pageable = pageable();
		RoomListSearchCriteria criteria = baseline(null, null, null, "Party", null, null, Set.of(), false);

		publicRoomQuery.findPublicRooms(criteria, pageable, REQUEST_TIME);

		verify(roomRepository).findPublicRoomsAt(
			null, false, false, false, REQUEST_TIME, null, true, "Party", false, Instant.EPOCH, false, Instant.EPOCH,
			false, 0, ALL_EXPERIENCE_LEVELS, false, PUBLIC_STATUSES,
			REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START), pageable);
	}

	@Test
	void startsAtFrom_필드를_그대로_JPQL_시작시각_하한_파라미터로_전달한다() {
		Pageable pageable = pageable();
		Instant startsAtFrom = Instant.parse("2099-01-01T00:00:00Z");
		RoomListSearchCriteria criteria = baseline(null, null, null, null, startsAtFrom, null, Set.of(), false);

		publicRoomQuery.findPublicRooms(criteria, pageable, REQUEST_TIME);

		verify(roomRepository).findPublicRoomsAt(
			null, false, false, false, REQUEST_TIME, null, false, "", true, startsAtFrom, false, Instant.EPOCH,
			false, 0, ALL_EXPERIENCE_LEVELS, false, PUBLIC_STATUSES,
			REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START), pageable);
	}

	@Test
	void startsAtTo_필드를_그대로_JPQL_시작시각_상한_파라미터로_전달한다() {
		Pageable pageable = pageable();
		Instant startsAtTo = Instant.parse("2099-01-02T00:00:00Z");
		RoomListSearchCriteria criteria = baseline(null, null, null, null, null, startsAtTo, Set.of(), false);

		publicRoomQuery.findPublicRooms(criteria, pageable, REQUEST_TIME);

		verify(roomRepository).findPublicRoomsAt(
			null, false, false, false, REQUEST_TIME, null, false, "", false, Instant.EPOCH, true, startsAtTo,
			false, 0, ALL_EXPERIENCE_LEVELS, false, PUBLIC_STATUSES,
			REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START), pageable);
	}

	@Test
	void minRemainingSeats_필드를_그대로_JPQL_최소_잔여좌석_파라미터로_전달한다() {
		Pageable pageable = pageable();
		RoomListSearchCriteria criteria = new RoomListSearchCriteria(
			null, null, null, null, null, null, 3, Set.of(), false);

		publicRoomQuery.findPublicRooms(criteria, pageable, REQUEST_TIME);

		verify(roomRepository).findPublicRoomsAt(
			null, false, false, false, REQUEST_TIME, null, false, "", false, Instant.EPOCH, false, Instant.EPOCH,
			true, 3, ALL_EXPERIENCE_LEVELS, false, PUBLIC_STATUSES,
			REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START), pageable);
	}

	@Test
	void experienceLevels_필드가_비어있지_않으면_그대로_전달하고_비어있으면_전체_레벨을_전달한다() {
		Pageable pageable = pageable();
		Set<ExperienceLevel> experienceLevels = Set.of(ExperienceLevel.BEGINNER_WELCOME);
		RoomListSearchCriteria criteria = new RoomListSearchCriteria(
			null, null, null, null, null, null, null, experienceLevels, false);

		publicRoomQuery.findPublicRooms(criteria, pageable, REQUEST_TIME);

		verify(roomRepository).findPublicRoomsAt(
			null, false, false, false, REQUEST_TIME, null, false, "", false, Instant.EPOCH, false, Instant.EPOCH,
			false, 0, experienceLevels, false, PUBLIC_STATUSES,
			REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START), pageable);
	}

	@Test
	void rulemasterOnly_필드를_그대로_JPQL_rulemasterOnly_파라미터로_전달한다() {
		Pageable pageable = pageable();
		RoomListSearchCriteria criteria = new RoomListSearchCriteria(
			null, null, null, null, null, null, null, Set.of(), true);

		publicRoomQuery.findPublicRooms(criteria, pageable, REQUEST_TIME);

		verify(roomRepository).findPublicRoomsAt(
			null, false, false, false, REQUEST_TIME, null, false, "", false, Instant.EPOCH, false, Instant.EPOCH,
			false, 0, ALL_EXPERIENCE_LEVELS, true, PUBLIC_STATUSES,
			REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START), pageable);
	}

	@Test
	void 공개_상태_후보군은_항상_모집중과_마감된_상태만_고정으로_전달한다() {
		Pageable pageable = pageable();
		RoomListSearchCriteria criteria = baseline(null, null, null, null, null, null, Set.of(), false);

		publicRoomQuery.findPublicRooms(criteria, pageable, REQUEST_TIME);

		verify(roomRepository).findPublicRoomsAt(
			null, false, false, false, REQUEST_TIME, null, false, "", false, Instant.EPOCH, false, Instant.EPOCH,
			false, 0, ALL_EXPERIENCE_LEVELS, false, PUBLIC_STATUSES,
			REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START), pageable);
	}

	@Test
	void 요청시각을_새로_생성하지_않고_주입받은_값을_JPQL_requestTime과_유효종료경계에_그대로_사용한다() {
		Pageable pageable = pageable();
		RoomListSearchCriteria criteria = baseline(null, null, null, null, null, null, Set.of(), false);
		Page<Room> page = new PageImpl<>(List.of(), pageable, 0);
		when(roomRepository.findPublicRoomsAt(
			null, false, false, false, REQUEST_TIME, null, false, "", false, Instant.EPOCH, false, Instant.EPOCH,
			false, 0, ALL_EXPERIENCE_LEVELS, false, PUBLIC_STATUSES,
			REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START), pageable))
			.thenReturn(page);

		Page<Room> result = publicRoomQuery.findPublicRooms(criteria, pageable, REQUEST_TIME);

		assertSame(page, result);
		verify(roomRepository).findPublicRoomsAt(
			null, false, false, false, REQUEST_TIME, null, false, "", false, Instant.EPOCH, false, Instant.EPOCH,
			false, 0, ALL_EXPERIENCE_LEVELS, false, PUBLIC_STATUSES,
			REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START), pageable);
	}

	private Pageable pageable() {
		return PageRequest.of(0, 10, Sort.by(Sort.Order.asc("startAt"), Sort.Order.asc("id")));
	}

	private RoomListSearchCriteria baseline(
		RoomType roomType,
		RoomStatus status,
		Long gameId,
		String keyword,
		Instant startsAtFrom,
		Instant startsAtTo,
		Set<ExperienceLevel> experienceLevels,
		boolean rulemasterOnly) {
		return new RoomListSearchCriteria(
			roomType, status, gameId, keyword, startsAtFrom, startsAtTo, null, experienceLevels, rulemasterOnly);
	}
}
