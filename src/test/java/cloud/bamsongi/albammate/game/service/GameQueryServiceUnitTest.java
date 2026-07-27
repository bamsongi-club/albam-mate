package cloud.bamsongi.albammate.game.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.bamsongi.albammate.game.GameSummary;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GameQueryServiceUnitTest {

    @Mock private GameRepository gameRepository;

    @InjectMocks private GameQueryService gameQueryService;

    @Test
    void 요약_조회는_전체_Game이_아닌_projection을_위임한다() {
        Long gameId = 1L;
        GameSummary expected = new GameSummary(gameId, 1001L, "카탄");
        when(gameRepository.findSummaryById(gameId)).thenReturn(Optional.of(expected));

        assertEquals(Optional.of(expected), gameQueryService.findSummaryById(gameId));

        verify(gameRepository).findSummaryById(gameId);
        verify(gameRepository, never()).findById(gameId);
    }
}
