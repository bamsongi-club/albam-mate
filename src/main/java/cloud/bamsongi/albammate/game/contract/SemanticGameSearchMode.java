package cloud.bamsongi.albammate.game.contract;

/**
 * 검색 결과가 어떤 방식으로 만들어졌는지 알려 주는 내부 상태다.
 *
 * 의미 검색이 정상인지, 키워드 검색으로 대체했는지, 둘 다 사용할 수 없는지를 구분한다. {@link #SPARSE_FALLBACK}은
 * dense candidate만 실패하고 구조화된 sparse candidate는 성공했을 때만 쓰며, 이름만 보는
 * {@link #LEXICAL_FALLBACK}과 의미가 다르다.
 */
public enum SemanticGameSearchMode {
	SEMANTIC,
	SPARSE_FALLBACK,
	LEXICAL_FALLBACK,
	UNAVAILABLE
}
