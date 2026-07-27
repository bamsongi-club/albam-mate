package cloud.bamsongi.albammate.game;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * 게임별 예정 모임 수를 제공하는 모듈 간 조회 계약이다.
 *
 * <p>방 데이터의 조회 구현은 {@code room} 모듈이 소유하고, 게임 목록은 이 계약만 사용한다.
 */
public interface UpcomingRoomCountQuery {

    Map<Long, Long> findUpcomingRoomCounts(Collection<Long> gameIds, Instant now);
}
