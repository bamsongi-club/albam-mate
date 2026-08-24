package cloud.bamsongi.albammate.game.contract;

import java.util.List;
import java.util.Objects;

/**
 * #871이 사용할 내부 검색 결과다.
 *
 * 사용자가 입력한 문장, 벡터, 유사도 점수처럼 응답에 필요 없는 검색 내부 정보는 담지 않는다.
 */
public record SemanticGameSearchResult(
	SemanticGameSearchMode mode,
	List<GameSummary> content,
	boolean hasNext) {

	public SemanticGameSearchResult {
		mode = Objects.requireNonNull(mode, "mode");
		content = List.copyOf(Objects.requireNonNull(content, "content"));
	}
}
