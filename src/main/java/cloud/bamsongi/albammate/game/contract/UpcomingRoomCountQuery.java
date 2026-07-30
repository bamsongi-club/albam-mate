package cloud.bamsongi.albammate.game.contract;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * 게임마다 앞으로 열릴 모임이 몇 개인지 알려준다.
 *
 * <p>모임(방) 데이터는 {@code room} 모듈이 관리하므로, 게임 목록은 방을 직접 조회하지 않고 이 인터페이스로 개수만 물어본다.
 *
 * <p>결과에 없는 게임 ID의 예정 모임 수는 0으로 해석한다.
 */
public interface UpcomingRoomCountQuery {

	/**
	 * 모든 게임을 대상으로, 아직 시작하지 않은 모임이 몇 개인지 센다.
	 *
	 * @param now 이 시각보다 늦게 시작하는 모임만 센다
	 * @return 알밤메이트 내부 게임 ID별 모임 수
	 */
	Map<Long, Long> findUpcomingRoomCounts(Instant now);

	/**
	 * 넘겨받은 게임만 대상으로, 아직 시작하지 않은 모임이 몇 개인지 센다.
	 *
	 * @param gameIds 개수를 셀 알밤메이트 내부 게임 ID 목록. {@code bggId}가 아니다
	 * @param now 이 시각보다 늦게 시작하는 모임만 센다
	 * @return 알밤메이트 내부 게임 ID별 모임 수
	 */
	Map<Long, Long> findUpcomingRoomCounts(Collection<Long> gameIds, Instant now);
}
