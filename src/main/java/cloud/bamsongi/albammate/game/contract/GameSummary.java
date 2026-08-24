package cloud.bamsongi.albammate.game.contract;

/**
 * 다른 모듈이 게임을 참조할 때 사용하는 최소 조회 값이다.
 *
 * @param id 알밤메이트 내부 게임 ID
 * @param bggId BoardGameGeek가 부여한 외부 게임 ID
 * @param name 서비스 표시용 게임 이름
 */
public record GameSummary(Long id, Long bggId, String name) {
}
