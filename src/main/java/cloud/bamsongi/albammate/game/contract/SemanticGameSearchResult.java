package cloud.bamsongi.albammate.game.contract;

import java.util.List;
import java.util.Objects;

/** 관련도 점수·vector·query 원문 없이 #871이 소비할 게임 검색 결과다. */
public record SemanticGameSearchResult(
	SemanticGameSearchMode mode,
	List<GameSummary> content,
	boolean hasNext) {

	public SemanticGameSearchResult {
		mode = Objects.requireNonNull(mode, "mode");
		content = List.copyOf(Objects.requireNonNull(content, "content"));
	}
}
