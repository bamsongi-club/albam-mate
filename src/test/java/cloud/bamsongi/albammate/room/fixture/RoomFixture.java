package cloud.bamsongi.albammate.room.fixture;

import java.time.Instant;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;

/** 랭킹 등 방 데이터가 필요한 테스트가 공유하는 최소 방 fixture다. */
public final class RoomFixture {

	private RoomFixture() {}

	public static Room create(Long hostUserId, RoomType roomType, Long gameId, Instant startAt) {
		return Room.create(
			hostUserId, roomType, "테스트 방", null, gameId, ExperienceLevel.ALL_LEVELS, false, startAt, "테스트 장소", 4);
	}
}
