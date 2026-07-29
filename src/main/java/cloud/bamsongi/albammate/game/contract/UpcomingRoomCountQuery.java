package cloud.bamsongi.albammate.game.contract;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * 게임별 예정 모임 수를 제공하는 모듈 간 조회 계약이다.
 *
 * <p>방 데이터의 조회 구현은 {@code room} 모듈이 소유하고, 게임 목록은 이 계약만 사용한다.
 */
public interface UpcomingRoomCountQuery {

	/**
	 * 기준 시각에 예정된 모임 수를 게임별로 조회한다.
	 *
	 * <p>반환 맵에 없는 게임 ID는 예정 모임이 0건인 것으로 해석한다.
	 *
	 * @param gameIds 알밤메이트 내부 게임 ID
	 * @param now 예정 여부를 판단할 기준 시각
	 * @return 예정 모임이 있는 게임 ID와 모임 수
	 */
	Map<Long, Long> findUpcomingRoomCounts(Collection<Long> gameIds, Instant now);
}
