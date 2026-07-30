package cloud.bamsongi.albammate.game.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import org.springframework.test.web.servlet.MockMvc;

import cloud.bamsongi.albammate.game.dto.GameDetail;
import cloud.bamsongi.albammate.game.dto.GameListItem;
import cloud.bamsongi.albammate.game.service.GameQueryService;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;

@WebMvcTest(controllers = GameController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, GameControllerTest.GameControllerTestConfiguration.class})
class GameControllerTest {

	@Autowired
	private GameQueryService gameQueryService;

	@Autowired
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		reset(gameQueryService);
	}

	@Test
	void 공개_게임_목록은_페이지와_게임_카드_필드를_반환한다() throws Exception {
		GameListItem item = new GameListItem(
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
		when(gameQueryService.findPage(any(), anyBoolean(), anyInt(), anyInt()))
			.thenReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 10), 1));

		mockMvc.perform(get("/api/games"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.content[0].id").value(1))
			.andExpect(jsonPath("$.data.content[0].bggId").value(1001))
			.andExpect(jsonPath("$.data.content[0].name").value("카탄"))
			.andExpect(jsonPath("$.data.content[0].englishName").value("Catan"))
			.andExpect(jsonPath("$.data.content[0].imageUrl").isEmpty())
			.andExpect(jsonPath("$.data.content[0].supportedPlayerCount").value("3~4명"))
			.andExpect(jsonPath("$.data.content[0].recommendedPlayerCount").doesNotExist())
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
	void 검색어와_페이지_파라미터를_서비스에_전달한다() throws Exception {
		PageRequest pageable = PageRequest.of(1, 1);
		when(gameQueryService.findPage(eq("Catan"), eq(false), eq(1), eq(1)))
			.thenReturn(new PageImpl<>(List.of(), pageable, 3));

		mockMvc.perform(get("/api/games?keyword=Catan&page=1&size=1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content").isEmpty())
			.andExpect(jsonPath("$.data.page").value(1))
			.andExpect(jsonPath("$.data.size").value(1))
			.andExpect(jsonPath("$.data.totalElements").value(3))
			.andExpect(jsonPath("$.data.totalPages").value(3))
			.andExpect(jsonPath("$.data.hasNext").value(true));

		verify(gameQueryService).findPage(eq("Catan"), eq(false), eq(1), eq(1));
	}

	@Test
	void 예정_모임_필터_true를_서비스에_전달한다() throws Exception {
		PageRequest pageable = PageRequest.of(0, 10);
		when(gameQueryService.findPage(isNull(), eq(true), eq(0), eq(10)))
			.thenReturn(new PageImpl<>(List.of(), pageable, 0));

		mockMvc.perform(get("/api/games?upcomingOnly=true"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content").isEmpty());

		verify(gameQueryService).findPage(isNull(), eq(true), eq(0), eq(10));
	}

	@Test
	void 예정_모임_필터_false를_서비스에_전달한다() throws Exception {
		PageRequest pageable = PageRequest.of(0, 10);
		when(gameQueryService.findPage(isNull(), eq(false), eq(0), eq(10)))
			.thenReturn(new PageImpl<>(List.of(), pageable, 0));

		mockMvc.perform(get("/api/games?upcomingOnly=false"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content").isEmpty());

		verify(gameQueryService).findPage(isNull(), eq(false), eq(0), eq(10));
	}

	@Test
	void size_상한_100은_성공한다() throws Exception {
		PageRequest pageable = PageRequest.of(0, 100);
		when(gameQueryService.findPage(isNull(), eq(false), eq(0), eq(100)))
			.thenReturn(new PageImpl<>(List.of(), pageable, 0));

		mockMvc.perform(get("/api/games?size=100"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.size").value(100))
			.andExpect(jsonPath("$.data.content").isEmpty());

		verify(gameQueryService).findPage(isNull(), eq(false), eq(0), eq(100));
	}

	@Test
	void 페이지_파라미터가_계약_범위를_벗어나면_VALIDATION_ERROR다() throws Exception {
		for (String query : List.of(
			"page=-1", "size=0", "size=101", "page=not-a-number", "size=not-a-number", "upcomingOnly=not-a-boolean")) {
			mockMvc.perform(get("/api/games?" + query))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		}
	}

	@Test
	void 빈_목록_parameter는_기본값으로_서비스에_전달한다() throws Exception {
		PageRequest pageable = PageRequest.of(0, 10);
		when(gameQueryService.findPage(isNull(), eq(false), eq(0), eq(10)))
			.thenReturn(new PageImpl<>(List.of(), pageable, 0));

		mockMvc.perform(get("/api/games?page=&size=&upcomingOnly="))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.page").value(0))
			.andExpect(jsonPath("$.data.size").value(10));

		verify(gameQueryService).findPage(isNull(), eq(false), eq(0), eq(10));
	}

	@Test
	void 게임_생성_수정_삭제_메서드는_노출하지_않는다() throws Exception {
		for (var request : List.of(post("/api/games"), patch("/api/games"), delete("/api/games"))) {
			mockMvc.perform(request)
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.code").value(ErrorCode.METHOD_NOT_ALLOWED.getCode()));
		}
	}

	@Test
	void 공개_게임_상세는_게임_목록_필드와_상세_필드를_반환한다() throws Exception {
		GameDetail detail = new GameDetail(
			1L,
			1001L,
			"카탄",
			"Catan",
			null,
			"3~4명",
			"전략",
			"60~90분",
			new BigDecimal("2.00"),
			2L,
			"카탄 기본판",
			"간단한 게임 설명",
			"상세한 게임 설명");
		when(gameQueryService.findById(1L)).thenReturn(detail);

		mockMvc.perform(get("/api/games/1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.id").value(1))
			.andExpect(jsonPath("$.data.bggId").value(1001))
			.andExpect(jsonPath("$.data.name").value("카탄"))
			.andExpect(jsonPath("$.data.englishName").value("Catan"))
			.andExpect(jsonPath("$.data.alias").value("카탄 기본판"))
			.andExpect(jsonPath("$.data.imageUrl").isEmpty())
			.andExpect(jsonPath("$.data.supportedPlayerCount").value("3~4명"))
			.andExpect(jsonPath("$.data.recommendedPlayerCount").doesNotExist())
			.andExpect(jsonPath("$.data.tag").value("전략"))
			.andExpect(jsonPath("$.data.estimatedPlayTime").value("60~90분"))
			.andExpect(jsonPath("$.data.complexity").value(2.0))
			.andExpect(jsonPath("$.data.upcomingRoomCount").value(2))
			.andExpect(jsonPath("$.data.description").value("간단한 게임 설명"))
			.andExpect(jsonPath("$.data.detailDescription").value("상세한 게임 설명"));

		verify(gameQueryService).findById(1L);
	}

	@Test
	void 없는_게임_상세는_GAME_NOT_FOUND다() throws Exception {
		when(gameQueryService.findById(999L))
			.thenThrow(new BusinessException(ErrorCode.GAME_NOT_FOUND));

		mockMvc.perform(get("/api/games/999"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value(ErrorCode.GAME_NOT_FOUND.getCode()));
	}

	@Test
	void 게임_ID가_1보다_작거나_형식이_아니면_VALIDATION_ERROR다() throws Exception {
		for (String gameId : List.of("0", "-1", "not-a-number")) {
			mockMvc.perform(get("/api/games/" + gameId))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class GameControllerTestConfiguration {

		@Bean
		GameQueryService gameQueryService() {
			return mock(GameQueryService.class);
		}
	}
}
