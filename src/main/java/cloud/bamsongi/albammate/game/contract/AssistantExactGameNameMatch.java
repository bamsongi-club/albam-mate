package cloud.bamsongi.albammate.game.contract;

import java.util.Objects;

/** 정규화한 정식 게임명 유일성 판정에만 쓰는 최소 projection이다. */
public record AssistantExactGameNameMatch(Long id, String name) {

	public AssistantExactGameNameMatch {
		id = Objects.requireNonNull(id, "id");
		name = Objects.requireNonNull(name, "name");
	}
}
