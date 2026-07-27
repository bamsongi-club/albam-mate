package cloud.bamsongi.albammate.game;

import java.util.Optional;

/**
 * 알밤메이트의 게임 조회 공개 계약입니다. {@code gameId}는 알밤메이트 내부 게임 ID입니다.
 *
 * <p>{@code room}등 타 모듈은 {@code game.repository}나 {@code game.entity}를 직접 참조하지 않고 이 공개 계약만 호출합니다.
 */
public interface GameQuery {

    /**
     * 게임이 존재하는지 확인합니다.
     *
     * @param gameId 알밤메이트 내부 게임 ID
     * @return 미존재 게임이면 {@code false}
     */
    boolean existsById(Long gameId);

    /**
     * 게임 요약을 조회합니다.
     *
     * @param gameId 알밤메이트 내부 게임 ID
     * @return 미존재 게임이면 {@link Optional#empty()}
     */
    Optional<GameSummary> findSummaryById(Long gameId);
}
