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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    GameListQueryService.class,
    GameDetailQueryService.class,
    RoomUpcomingRoomCountQuery.class,
    JpaConfig.class,
    TimeConfig.class,
    GameDetailQueryServiceIntegrationTest.FixedClockTestConfiguration.class
})
class GameDetailQueryServiceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");

    @Autowired private GameRepository gameRepository;

    @Autowired private GameListQueryService gameListQueryService;

    @Autowired private GameDetailQueryService gameDetailQueryService;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void 상세와_목록은_같은_게임의_공통_필드와_예정_모임_수를_반환한다() {
        long hostUserId = insertHostUser();
        Game game = gameRepository.saveAndFlush(GameFixture.valid(1001L, "카탄"));

        insertRoom(
                hostUserId,
                game.getId(),
                RoomType.GAME_FOCUSED,
                NOW.plusSeconds(1),
                RoomStatus.RECRUITING);
        insertRoom(
                hostUserId,
                game.getId(),
                RoomType.GAME_FOCUSED,
                NOW.plusSeconds(2),
                RoomStatus.CLOSED);
        insertRoom(
                hostUserId,
                game.getId(),
                RoomType.GAME_FOCUSED,
                NOW.plusSeconds(3),
                RoomStatus.CANCELED);
        insertRoom(
                hostUserId,
                game.getId(),
                RoomType.PERSON_FOCUSED,
                NOW.plusSeconds(4),
                RoomStatus.RECRUITING);

        GameListItem listItem =
                gameListQueryService
                        .findPage(null, PageRequest.of(0, 10, Sort.by("name", "id")))
                        .getContent()
                        .getFirst();
        GameDetail detail = gameDetailQueryService.findById(game.getId());

        assertEquals(listItem.id(), detail.id());
        assertEquals(listItem.bggId(), detail.bggId());
        assertEquals(listItem.name(), detail.name());
        assertEquals(listItem.englishName(), detail.englishName());
        assertEquals(listItem.imageUrl(), detail.imageUrl());
        assertEquals(listItem.recommendedPlayerCount(), detail.recommendedPlayerCount());
        assertEquals(listItem.tag(), detail.tag());
        assertEquals(listItem.estimatedPlayTime(), detail.estimatedPlayTime());
        assertEquals(listItem.complexity(), detail.complexity());
        assertEquals(listItem.upcomingRoomCount(), detail.upcomingRoomCount());
        assertEquals(2L, detail.upcomingRoomCount());
        assertEquals("게임 설명", detail.description());
        assertEquals("게임 상세 설명", detail.detailDescription());
    }

    private long insertHostUser() {
        String email = "game-detail-host@example.com";
        jdbcTemplate.update(
                """
                insert into users
                    (email, password_hash, nickname, created_at, updated_at)
                values
                    (?, 'test-hash', '게임 상세 테스트 호스트',
                     TIMESTAMP WITH TIME ZONE '2026-07-26T00:00:00Z',
                     TIMESTAMP WITH TIME ZONE '2026-07-26T00:00:00Z')
                """,
                email);

        return jdbcTemplate.queryForObject(
                "select id from users where email = ?", Long.class, email);
    }

    private void insertRoom(
            long hostUserId, Long gameId, RoomType roomType, Instant startAt, RoomStatus status) {
        jdbcTemplate.update(
                """
                insert into rooms
                    (game_id, host_user_id, room_type, title, experience_level,
                     is_rulemaster_led, capacity, active_participant_count, start_at, place,
                     status, created_at, updated_at)
                values
                    (?, ?, ?, '게임 상세 집계 테스트 방', 'ALL_LEVELS', false, 2, 0,
                     CAST(? AS TIMESTAMP WITH TIME ZONE), '테스트 장소', ?,
                     TIMESTAMP WITH TIME ZONE '2026-07-26T00:00:00Z',
                     TIMESTAMP WITH TIME ZONE '2026-07-26T00:00:00Z')
                """,
                gameId,
                hostUserId,
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
