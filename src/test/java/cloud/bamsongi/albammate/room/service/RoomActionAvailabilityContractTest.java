package cloud.bamsongi.albammate.room.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.room.dto.MyRoomListItem;
import cloud.bamsongi.albammate.room.dto.NicknameSummary;
import cloud.bamsongi.albammate.room.dto.ParticipantRoomResponse;
import cloud.bamsongi.albammate.room.dto.PublicRoomResponse;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.MyRole;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;

class RoomActionAvailabilityContractTest {

	@Test
	void Room은_저장된_ACTIVE_인원만으로_표시_인원과_잔여_좌석을_계산한다() {
		Room room = room(2, RoomStatus.RECRUITING);
		assertEquals(1, room.getTotalParticipantCount());
		assertEquals(2, room.getRemainingRecruitmentSeats());
		ReflectionTestUtils.setField(room, "activeParticipantCount", 3);
		assertEquals(4, room.getTotalParticipantCount());
		assertEquals(-1, room.getRemainingRecruitmentSeats());
	}

	@Test
	void evaluator는_요청자_관계_상태_시각_잔여석에_따라_상호_배타적으로_판정한다() {
		RoomActionAvailabilityEvaluator evaluator = new RoomActionAvailabilityEvaluator();
		Room recruiting = room(2, RoomStatus.RECRUITING);
		assertEquals(new RoomActionAvailability(true, false),
			availability(evaluator, recruiting, true, false, false, false));
		assertEquals(RoomActionAvailability.UNAVAILABLE,
			availability(evaluator, recruiting, false, false, false, false));
		assertEquals(RoomActionAvailability.UNAVAILABLE, availability(evaluator, recruiting, true, true, false, false));
		assertEquals(RoomActionAvailability.UNAVAILABLE, availability(evaluator, recruiting, true, false, true, false));
		assertEquals(RoomActionAvailability.UNAVAILABLE, availability(evaluator, recruiting, true, false, false, true));
		assertEquals(RoomActionAvailability.UNAVAILABLE,
			availability(evaluator, recruiting, true, false, false, false, recruiting.getStartAt()));
		Room closed = room(1, RoomStatus.CLOSED);
		ReflectionTestUtils.setField(closed, "activeParticipantCount", 1);
		assertEquals(new RoomActionAvailability(false, true),
			availability(evaluator, closed, true, false, false, false));
		assertEquals(RoomActionAvailability.UNAVAILABLE,
			availability(evaluator, room(1, RoomStatus.CLOSED), true, false, false, false));
		Room fullRecruiting = room(1, RoomStatus.RECRUITING);
		ReflectionTestUtils.setField(fullRecruiting, "activeParticipantCount", 1);
		assertEquals(RoomActionAvailability.UNAVAILABLE,
			availability(evaluator, fullRecruiting, true, false, false, false));
		assertEquals(RoomActionAvailability.UNAVAILABLE, availability(evaluator, closed, true, false, false, true));
		assertEquals(RoomActionAvailability.UNAVAILABLE,
			availability(evaluator, room(1, RoomStatus.CANCELED), true, false, false, false));
		assertEquals(RoomActionAvailability.UNAVAILABLE,
			availability(evaluator, room(1, RoomStatus.FINISHED), true, false, false, false));
	}

	@Test
	void 세_공개_조회_DTO는_waitlistable을_포함하고_참가_응답에는_포함하지_않는다() {
		Room room = room(3, RoomStatus.RECRUITING);
		ReflectionTestUtils.setField(room, "id", 7L);
		RoomActionAvailability availability = new RoomActionAvailability(false, true);
		GameSummary game = new GameSummary(3L, 1003L, "카탄");
		assertEquals(true, PublicRoomResponse.from(room, game, availability).waitlistable());
		assertEquals(true, ParticipantRoomResponse.from(room, game, availability, MyRole.HOST,
			new NicknameSummary("방장", null), List.of(new NicknameSummary("방장", null))).waitlistable());
		assertEquals(true, MyRoomListItem.from(room, game, availability, MyRole.JOINED,
			ParticipationStatus.ACTIVE).waitlistable());
		RoomParticipationResponse participation = RoomParticipationResponse.from(room, ParticipationStatus.ACTIVE);
		assertEquals(1, participation.participantCount());
		assertEquals(3, participation.remainingRecruitmentSeats());
		assertEquals(5, RoomParticipationResponse.class.getRecordComponents().length);
	}

	private RoomActionAvailability availability(
		RoomActionAvailabilityEvaluator evaluator,
		Room room,
		boolean authenticated,
		boolean host,
		boolean activeParticipant,
		boolean waiting) {
		return availability(evaluator, room, authenticated, host, activeParticipant, waiting,
			Instant.parse("2099-01-01T09:00:00Z"));
	}

	private RoomActionAvailability availability(
		RoomActionAvailabilityEvaluator evaluator, Room room, boolean authenticated, boolean host,
		boolean activeParticipant, boolean waiting, Instant requestTime) {
		return evaluator.evaluate(new RoomActionAvailabilityFacts(
			room, requestTime, authenticated, host, activeParticipant, waiting));
	}

	private Room room(int capacity, RoomStatus status) {
		Room room = Room.create(1L, RoomType.PERSON_FOCUSED, "방", null, null,
			ExperienceLevel.ALL_LEVELS, false, Instant.parse("2099-01-01T10:00:00Z"), "서울", capacity);
		ReflectionTestUtils.setField(room, "status", status);
		return room;
	}
}
