package cloud.bamsongi.albammate.room.service;

import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.room.enums.RoomStatus;

/** Room의 현재 상태와 요청자 관계를 공통 규칙으로 행동 가능성으로 바꾼다. */
@Component
public class RoomActionAvailabilityEvaluator {

	public RoomActionAvailability evaluate(RoomActionAvailabilityFacts facts) {
		if (!facts.authenticated() || facts.host() || facts.activeParticipant() || facts.waiting()) {
			return RoomActionAvailability.UNAVAILABLE;
		}
		if (!facts.requestTime().isBefore(facts.room().getStartAt())) {
			return RoomActionAvailability.UNAVAILABLE;
		}

		if (facts.room().getStatus() == RoomStatus.RECRUITING
			&& facts.room().getRemainingRecruitmentSeats() >= 1) {
			return new RoomActionAvailability(true, false);
		}
		if (facts.room().getStatus() == RoomStatus.CLOSED
			&& facts.room().getRemainingRecruitmentSeats() == 0
			&& !facts.waiting()) {
			return new RoomActionAvailability(false, true);
		}
		return RoomActionAvailability.UNAVAILABLE;
	}
}
