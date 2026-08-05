package cloud.bamsongi.albammate.room.service.query;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.MyRoomWaitlistResponse;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 상태 보정 커밋 뒤 현재 사용자의 대기 상태·순번을 한 읽기 트랜잭션에서 조회한다. */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class RoomWaitlistReadService {

	@NonNull private final RoomRepository roomRepository;
	@NonNull private final RoomWaitlistRepository roomWaitlistRepository;

	@Transactional(readOnly = true)
	public MyRoomWaitlistResponse findMyWaitlist(long currentUserId, long roomId) {
		if (!roomRepository.existsById(roomId)) {
			throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
		}
		return roomWaitlistRepository.findStateWithPositionByRoomIdAndUserId(roomId, currentUserId)
			.map(state -> new MyRoomWaitlistResponse(roomId, state.getStatus(), state.getPosition()))
			.orElseThrow(() -> new BusinessException(ErrorCode.WAITLIST_ENTRY_NOT_FOUND));
	}
}
