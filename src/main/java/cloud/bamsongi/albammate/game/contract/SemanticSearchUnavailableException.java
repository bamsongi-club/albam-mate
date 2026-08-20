package cloud.bamsongi.albammate.game.contract;

/** 승인된 dense 후보 source가 일시적으로 사용할 수 없음을 나타낸다. */
public class SemanticSearchUnavailableException extends RuntimeException {

	public SemanticSearchUnavailableException() {
		super();
	}
}
