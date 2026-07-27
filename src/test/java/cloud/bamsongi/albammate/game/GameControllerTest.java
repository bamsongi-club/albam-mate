package cloud.bamsongi.albammate.game;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = GameController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, GameControllerTest.GameControllerTestConfiguration.class})
class GameControllerTest {

    @Autowired private GameListQueryService gameListQueryService;

    @Autowired private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reset(gameListQueryService);
    }

    @Test
    void 공개_게임_목록은_페이지와_게임_카드_필드를_반환한다() throws Exception {
        GameListItem item =
                new GameListItem(
                        1L,
                        1001L,
                        "카탄",
                        "Catan",
                        null,
                        "3~4명",
                        "전략",
                        "60~90분",
                        new BigDecimal("2.00"),
                        0L);
        when(gameListQueryService.findPage(any(), any()))
                .thenReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/games"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.content[0].id").value(1))
                .andExpect(jsonPath("$.data.content[0].bggId").value(1001))
                .andExpect(jsonPath("$.data.content[0].name").value("카탄"))
                .andExpect(jsonPath("$.data.content[0].englishName").value("Catan"))
                .andExpect(jsonPath("$.data.content[0].imageUrl").isEmpty())
                .andExpect(jsonPath("$.data.content[0].recommendedPlayerCount").value("3~4명"))
                .andExpect(jsonPath("$.data.content[0].tag").value("전략"))
                .andExpect(jsonPath("$.data.content[0].estimatedPlayTime").value("60~90분"))
                .andExpect(jsonPath("$.data.content[0].complexity").value(2.0))
                .andExpect(jsonPath("$.data.content[0].upcomingRoomCount").value(0))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void 검색어와_페이지_파라미터를_전달하고_이름과_ID로_고정_정렬한다() throws Exception {
        PageRequest pageable =
                PageRequest.of(1, 1, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id")));
        when(gameListQueryService.findPage(eq("Catan"), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 3));

        mockMvc.perform(get("/api/games?keyword=Catan&page=1&size=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(true));

        verify(gameListQueryService).findPage(eq("Catan"), eq(pageable));
    }

    @Test
    void 페이지_파라미터가_계약_범위를_벗어나면_VALIDATION_ERROR다() throws Exception {
        for (String query : List.of("page=-1", "size=0", "size=101")) {
            mockMvc.perform(get("/api/games?" + query))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
        }
    }

    @Test
    void 게임_생성_수정_삭제_메서드는_노출하지_않는다() throws Exception {
        for (var request : List.of(post("/api/games"), patch("/api/games"), delete("/api/games"))) {
            mockMvc.perform(request)
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value(ErrorCode.METHOD_NOT_ALLOWED.getCode()));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GameControllerTestConfiguration {

        @Bean
        GameListQueryService gameListQueryService() {
            return mock(GameListQueryService.class);
        }
    }
}
