package cloud.bamsongi.albammate.room.service.query;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.room.dto.MyRoomWaitlistResponse;
import cloud.bamsongi.albammate.room.statuscorrection.RoomStatusCorrectionCoordinator;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 기준 시각의 ROOM 상태 보정 뒤 본인 대기 상태를 조회한다. */
@Service
@RequiredArgsConstructor
public class RoomWaitlistQueryService {

	@NonNull private final RoomStatusCorrectionCoordinator statusCorrectionCoordinator;
	@NonNull private final RoomWaitlistReadService readService;
	@NonNull private final Clock clock;

	public MyRoomWaitlistResponse findMyWaitlist(long currentUserId, long roomId) {
		Instant requestTime = Instant.now(clock);
		statusCorrectionCoordinator.correctRoom(roomId, requestTime);
		return readService.findMyWaitlist(currentUserId, roomId);
	}
}
