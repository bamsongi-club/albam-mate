package cloud.bamsongi.albammate.game.contract;

/**
 * 의미 후보를 읽는 인덱스나 모델 서비스를 지금 사용할 수 없다는 뜻이다.
 *
 * 이 경우에만 키워드 검색으로 대체하고, 그 밖의 오류는 fallback으로 숨기지 않는다.
 */
public class SemanticSearchUnavailableException extends RuntimeException {

	public SemanticSearchUnavailableException() {
		super();
	}
}
