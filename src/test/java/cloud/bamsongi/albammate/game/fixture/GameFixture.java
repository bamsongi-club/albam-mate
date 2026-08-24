package cloud.bamsongi.albammate.game.fixture;

import cloud.bamsongi.albammate.game.entity.Game;

public final class GameFixture {

	private GameFixture() {}

	public static Game valid() {
		return valid(1001L, "카탄");
	}

	public static Game valid(long bggId, String name) {
		return new Game(bggId, name, "Catan", "3~4명", "전략", "60~90분", "게임 설명", "게임 상세 설명");
	}
}
