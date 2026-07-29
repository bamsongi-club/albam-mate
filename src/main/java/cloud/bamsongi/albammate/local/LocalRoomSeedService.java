package cloud.bamsongi.albammate.local;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** local 프로필의 식별 가능한 공개 모임만 반복 가능하게 준비한다. */
@Service
@Profile("local")
@RequiredArgsConstructor
public class LocalRoomSeedService {

	private static final int SEED_ROOM_COUNT_PER_TYPE = 30;
	private static final int DEFAULT_CAPACITY = 6;
	private static final String HOST_EMAIL = "local.seed.host@albammate.local";
	private static final String HOST_NICKNAME = "로컬 모임지기";
	private static final String UNUSABLE_PASSWORD_HASH = "{bcrypt}$2a$10$6fzHq4LYhgKdwPTfFGRY8eV4JC7GDmK3eE3eM9WlQqKP7sFiSWOmK";
	private static final long LOCAL_GAME_BGG_ID_BASE = -9_000_000_000L;

	private final JdbcTemplate jdbcTemplate;
	private final Clock clock;

	/** 시더 데이터 전체를 하나의 트랜잭션으로 맞춘다. */
	@Transactional
	public void seed() {
		Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
		long hostUserId = findOrCreateHost(now);
		List<SeedGame> games = findSeedGames(now);

		for (int index = 1; index <= SEED_ROOM_COUNT_PER_TYPE; index++) {
			upsertRoom(gameRoom(index, games.get(index - 1), now), hostUserId, now);
			upsertRoom(personRoom(index, now), hostUserId, now);
		}
	}

	private long findOrCreateHost(Instant now) {
		jdbcTemplate.update(
			"""
				insert into users (email, password_hash, nickname, created_at, updated_at)
				select ?, ?, ?, ?, ?
				where not exists (select 1 from users where email = ?)
				""",
			HOST_EMAIL,
			UNUSABLE_PASSWORD_HASH,
			HOST_NICKNAME,
			timestamp(now),
			timestamp(now),
			HOST_EMAIL);
		return jdbcTemplate.queryForObject(
			"select id from users where email = ?", Long.class, HOST_EMAIL);
	}

	private List<SeedGame> findSeedGames(Instant now) {
		List<SeedGame> games = new ArrayList<>(jdbcTemplate.query(
			"select id from games where bgg_id > 0 order by id limit ?",
			(resultSet, rowNumber) -> new SeedGame(resultSet.getLong("id")),
			SEED_ROOM_COUNT_PER_TYPE));
		int requiredLocalGames = SEED_ROOM_COUNT_PER_TYPE - games.size();
		ensureLocalGames(requiredLocalGames, now);
		games.addAll(findLocalGames(requiredLocalGames));
		return games;
	}

	private void ensureLocalGames(int requiredLocalGames, Instant now) {
		for (int index = 1; index <= requiredLocalGames; index++) {
			long bggId = localGameBggId(index);
			jdbcTemplate.update(
				"""
					insert into games (
					    bgg_id, name, english_name, supported_player_count, tag,
					    estimated_play_time, description, detail_description, created_at, updated_at)
					select ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
					where not exists (select 1 from games where bgg_id = ?)
					""",
				bggId,
				"[LOCAL] 참조 게임 %02d".formatted(index),
				"[LOCAL] Reference Game %02d".formatted(index),
				"2~4명",
				"LOCAL",
				"60분",
				"로컬 개발 환경의 모임 시드용 참조 게임입니다.",
				"운영 카탈로그가 30개 미만일 때만 사용하는 로컬 전용 참조 게임입니다.",
				timestamp(now),
				timestamp(now),
				bggId);
		}
	}

	private List<SeedGame> findLocalGames(int requiredLocalGames) {
		return jdbcTemplate.query(
			"select id from games where bgg_id between ? and ? order by bgg_id desc limit ?",
			(resultSet, rowNumber) -> new SeedGame(resultSet.getLong("id")),
			localGameBggId(SEED_ROOM_COUNT_PER_TYPE),
			localGameBggId(1),
			requiredLocalGames);
	}

	private void upsertRoom(SeedRoom room, long hostUserId, Instant now) {
		int activeParticipantCount = jdbcTemplate.queryForObject(
			"""
				select count(*)
				from participations participation
				join rooms seed_room on seed_room.id = participation.room_id
				where seed_room.host_user_id = ? and seed_room.title = ? and participation.status = 'ACTIVE'
				""",
			Integer.class,
			hostUserId,
			room.title());
		int capacity = Math.max(DEFAULT_CAPACITY, activeParticipantCount);

		jdbcTemplate.update(
			"""
				update rooms
				set game_id = ?, room_type = ?, description = ?, experience_level = 'BEGINNER_WELCOME',
				    is_rulemaster_led = true, region = '홍대', capacity = ?, active_participant_count = ?,
				    start_at = ?, place = ?, status = case when ? >= ? then 'CLOSED' else 'RECRUITING' end,
				    version = version + 1, updated_at = ?
				where host_user_id = ? and title = ?
				""",
			room.gameId(),
			room.type(),
			room.description(),
			capacity,
			activeParticipantCount,
			timestamp(room.startAt()),
			"홍대입구역 보드게임 카페",
			activeParticipantCount,
			DEFAULT_CAPACITY,
			timestamp(now),
			hostUserId,
			room.title());

		jdbcTemplate.update(
			"""
				insert into rooms (
				    game_id, host_user_id, room_type, title, description, experience_level,
				    is_rulemaster_led, region, capacity, active_participant_count, start_at,
				    place, status, version, created_at, updated_at)
				select ?, ?, ?, ?, ?, 'BEGINNER_WELCOME', true, '홍대',
				       ?, ?, ?, ?, case when ? >= ? then 'CLOSED' else 'RECRUITING' end,
				       0, ?, ?
				where not exists (select 1 from rooms where host_user_id = ? and title = ?)
				""",
			room.gameId(),
			hostUserId,
			room.type(),
			room.title(),
			room.description(),
			capacity,
			activeParticipantCount,
			timestamp(room.startAt()),
			"홍대입구역 보드게임 카페",
			activeParticipantCount,
			DEFAULT_CAPACITY,
			timestamp(now),
			timestamp(now),
			hostUserId,
			room.title());
	}

	private SeedRoom gameRoom(int index, SeedGame game, Instant now) {
		return new SeedRoom(
			"GAME_FOCUSED",
			"[LOCAL] 게임 중심 모임 %02d".formatted(index),
			"로컬 개발용 게임 중심 모임입니다. 편하게 참여해 보세요.",
			game.id(),
			now.plus(index, ChronoUnit.DAYS).plus(4, ChronoUnit.HOURS));
	}

	private SeedRoom personRoom(int index, Instant now) {
		return new SeedRoom(
			"PERSON_FOCUSED",
			"[LOCAL] 사람 중심 모임 %02d".formatted(index),
			"로컬 개발용 사람 중심 모임입니다. 게임은 현장에서 함께 정해요.",
			null,
			now.plus(index, ChronoUnit.DAYS).plus(18, ChronoUnit.HOURS));
	}

	private long localGameBggId(int index) {
		return LOCAL_GAME_BGG_ID_BASE - index;
	}

	private Timestamp timestamp(Instant instant) {
		return Timestamp.from(instant);
	}

	private record SeedGame(long id) {
	}

	private record SeedRoom(String type, String title, String description, Long gameId, Instant startAt) {
	}
}
