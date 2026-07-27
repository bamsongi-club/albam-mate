package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.fixture.GameFixture;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.global.config.JpaConfig;
import cloud.bamsongi.albammate.global.config.TimeConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@Import({GameQueryService.class, JpaConfig.class, TimeConfig.class})
class GameQueryServiceTest {

    @Autowired private GameRepository gameRepository;

    @Autowired private GameQuery gameQuery;

    @Test
    void 존재하는_ID는_true이고_없는_ID는_false다() {
        Game savedGame = gameRepository.saveAndFlush(GameFixture.valid());

        assertTrue(gameQuery.existsById(savedGame.getId()));
        assertFalse(gameQuery.existsById(999_999L));
    }

    @Test
    void 존재하는_ID는_id_bggId_name만_요약하고_없는_ID는_empty다() {
        Game savedGame = gameRepository.saveAndFlush(GameFixture.valid());

        GameSummary summary = gameQuery.findSummaryById(savedGame.getId()).orElseThrow();

        assertEquals(savedGame.getId(), summary.id());
        assertEquals(savedGame.getBggId(), summary.bggId());
        assertEquals(savedGame.getName(), summary.name());
        assertArrayEquals(
                new String[] {"id", "bggId", "name"},
                java.util.Arrays.stream(GameSummary.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toArray(String[]::new));
        assertTrue(gameQuery.findSummaryById(999_999L).isEmpty());
    }

    @Test
    void 조회_서비스는_읽기_전용_트랜잭션을_사용한다() {
        Transactional transactional = GameQueryService.class.getAnnotation(Transactional.class);

        assertNotNull(transactional);
        assertTrue(transactional.readOnly());
    }
}
