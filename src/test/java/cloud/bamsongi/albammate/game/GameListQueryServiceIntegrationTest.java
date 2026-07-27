package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.fixture.GameFixture;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.global.config.JpaConfig;
import cloud.bamsongi.albammate.global.config.TimeConfig;
import cloud.bamsongi.albammate.room.RoomUpcomingRoomCountQuery;
import cloud.bamsongi.albammate.room.entity.RoomStatus;
import cloud.bamsongi.albammate.room.entity.RoomType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    GameListQueryService.class,
    RoomUpcomingRoomCountQuery.class,
    JpaConfig.class,
    TimeConfig.class,
    GameListQueryServiceIntegrationTest.FixedClockTestConfiguration.class
})
class GameListQueryServiceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");
    private static final long HOST_USER_ID = 1L;

    @Autowired private GameRepository gameRepository;

    @Autowired private GameListQueryService gameListQueryService;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void 예정_모임_수는_게임중심_미래_미종료방만_게임별로_집계한다() {
        insertHostUser();
        Game gameWithTwoRooms = gameRepository.saveAndFlush(GameFixture.valid(1001L, "카탄"));
        Game gameWithOneRoom = gameRepository.saveAndFlush(GameFixture.valid(1002L, "아줄"));
        Game gameWithoutUpcomingRoom = gameRepository.saveAndFlush(GameFixture.valid(1003L, "윙스팬"));

        insertRoom(
                gameWithTwoRooms.getId(),
                RoomType.GAME_FOCUSED,
                NOW.plusSeconds(1),
                RoomStatus.RECRUITING);
        insertRoom(
                gameWithTwoRooms.getId(),
                RoomType.GAME_FOCUSED,
                NOW.plusSeconds(2),
                RoomStatus.CLOSED);
        insertRoom(
                gameWithTwoRooms.getId(),
                RoomType.GAME_FOCUSED,
                NOW.plusSeconds(3),
                RoomStatus.CANCELED);
        insertRoom(
                gameWithTwoRooms.getId(),
                RoomType.GAME_FOCUSED,
                NOW.plusSeconds(4),
                RoomStatus.FINISHED);
        insertRoom(
                gameWithTwoRooms.getId(),
                RoomType.GAME_FOCUSED,
                NOW.minusSeconds(1),
                RoomStatus.RECRUITING);
        insertRoom(gameWithTwoRooms.getId(), RoomType.GAME_FOCUSED, NOW, RoomStatus.RECRUITING);
        insertRoom(
                gameWithTwoRooms.getId(),
                RoomType.PERSON_FOCUSED,
                NOW.plusSeconds(5),
                RoomStatus.RECRUITING);
        insertRoom(
                gameWithOneRoom.getId(),
                RoomType.GAME_FOCUSED,
                NOW.plusSeconds(6),
                RoomStatus.RECRUITING);

        Page<GameListItem> result =
                gameListQueryService.findPage(null, PageRequest.of(0, 10, Sort.by("name", "id")));

        Map<Long, Long> upcomingRoomCounts =
                result.getContent().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        GameListItem::id, GameListItem::upcomingRoomCount));

        assertEquals(
                Map.of(
                        gameWithTwoRooms.getId(), 2L,
                        gameWithOneRoom.getId(), 1L,
                        gameWithoutUpcomingRoom.getId(), 0L),
                upcomingRoomCounts);
    }

    private void insertHostUser() {
        jdbcTemplate.update(
                """
                insert into users
                    (id, email, password_hash, nickname, created_at, updated_at)
                values
                    (?, 'game-list-host@example.com', 'test-hash', '게임 목록 테스트 호스트',
                     TIMESTAMP WITH TIME ZONE '2026-07-26T00:00:00Z',
                     TIMESTAMP WITH TIME ZONE '2026-07-26T00:00:00Z')
                """,
                HOST_USER_ID);
    }

    private void insertRoom(Long gameId, RoomType roomType, Instant startAt, RoomStatus status) {
        jdbcTemplate.update(
                """
                insert into rooms
                    (game_id, host_user_id, room_type, title, experience_level,
                     is_rulemaster_led, capacity, active_participant_count, start_at, place,
                     status, created_at, updated_at)
                values
                    (?, ?, ?, '게임 목록 집계 테스트 방', 'ALL_LEVELS', false, 2, 0,
                     CAST(? AS TIMESTAMP WITH TIME ZONE), '테스트 장소', ?,
                     TIMESTAMP WITH TIME ZONE '2026-07-26T00:00:00Z',
                     TIMESTAMP WITH TIME ZONE '2026-07-26T00:00:00Z')
                """,
                gameId,
                HOST_USER_ID,
                roomType.name(),
                startAt.toString(),
                status.name());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockTestConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
