package cloud.bamsongi.albammate.game.contract;

import java.time.Instant;
import java.util.List;

/**
 * 게임별 인기 랭킹 집계를 방 데이터에서 얻는다.
 *
 * <p>모임(방) 데이터는 {@code room} 모듈이 관리하므로, {@code game}은 방을 직접 조회하지 않고 이 인터페이스로 집계된 순위만
 * 받는다. 두 조회 모두 집계 모임 수 내림차순, 게임 ID 오름차순으로 정렬한 뒤 {@code limit}개까지만 돌려준다.
 */
public interface GameRankingQuery {

	/**
	 * 기간 조건 없이 게임별 전체 집계 모임 수를 상위 {@code limit}개까지 조회한다.
	 *
	 * @param limit 반환할 최대 게임 수
	 * @return 집계 모임 수 내림차순, 게임 ID 오름차순으로 정렬된 게임별 집계
	 */
	List<GameRoomCount> findOverallRanking(int limit);

	/**
	 * {@code [fromInclusive, toExclusive)}에 시작하는 방만 집계해 상위 {@code limit}개까지 조회한다.
	 *
	 * @param fromInclusive 집계에 포함할 시작 시각의 하한(포함)
	 * @param toExclusive 집계에서 제외할 시작 시각의 상한(제외)
	 * @param limit 반환할 최대 게임 수
	 * @return 집계 모임 수 내림차순, 게임 ID 오름차순으로 정렬된 게임별 집계
	 */
	List<GameRoomCount> findRankingByPeriod(Instant fromInclusive, Instant toExclusive, int limit);

	/**
	 * 게임 하나의 집계 모임 수다.
	 *
	 * @param gameId 알밤메이트 내부 게임 ID
	 * @param roomCount 집계 대상 방 수
	 */
	record GameRoomCount(Long gameId, long roomCount) {
	}
}
