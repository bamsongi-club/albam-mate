package cloud.bamsongi.albammate.game.contract;

/**
 * #871이 API 응답을 만들기 전에 쓰는 내부 의미 검색 서비스다.
 *
 * 이 서비스가 내보내는 것은 검증을 마친 게임 목록과 결과 상태뿐이다. 벡터나 유사도 점수는 외부 API로 나가지 않는다.
 */
public interface SemanticGameSearch {

	SemanticGameSearchResult search(SemanticGameSearchQuery query);
}
