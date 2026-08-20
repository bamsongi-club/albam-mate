package cloud.bamsongi.albammate.game.contract;

/** #871이 의미 검색 결과를 조립하기 전에 호출하는 game 내부 조회 경계다. */
public interface SemanticGameSearch {

	SemanticGameSearchResult search(SemanticGameSearchQuery query);
}
