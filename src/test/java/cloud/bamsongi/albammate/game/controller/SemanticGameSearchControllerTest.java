package cloud.bamsongi.albammate.game.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import cloud.bamsongi.albammate.game.contract.SemanticGameSearchMode;
import cloud.bamsongi.albammate.game.dto.SemanticGameSearchRequest;
import cloud.bamsongi.albammate.game.dto.SemanticGameSearchResponse;
import cloud.bamsongi.albammate.game.service.SemanticGameSearchQueryService;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;
import cloud.bamsongi.albammate.global.exception.UnauthenticatedException;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;

@WebMvcTest(controllers = SemanticGameSearchController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, SemanticGameSearchControllerTest.TestConfig.class})
class SemanticGameSearchControllerTest {

	@Autowired
	private SemanticGameSearchQueryService semanticGameSearchQueryService;

	@Autowired
	private CurrentUserAccessor currentUserAccessor;

	@Autowired
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		reset(semanticGameSearchQueryService);
		reset(currentUserAccessor);
		when(currentUserAccessor.currentUserId()).thenReturn(Optional.empty());
	}

	@Test
	void T1_빈_query는_조회없이_VALIDATION_ERROR다() throws Exception {
		mockMvc.perform(get("/api/games/semantic-search"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));

		verifyNoInteractions(semanticGameSearchQueryService);
	}

	@Test
	void T1_허용_길이를_넘는_query는_조회없이_VALIDATION_ERROR다() throws Exception {
		String tooLong = "가".repeat(SemanticGameSearchRequest.MAX_QUERY_LENGTH + 1);

		mockMvc.perform(get("/api/games/semantic-search").param("query", tooLong))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));

		verifyNoInteractions(semanticGameSearchQueryService);
	}

	@Test
	void T1_잘못된_page_size는_조회없이_VALIDATION_ERROR다() throws Exception {
		for (String query : List.of("query=협력 게임&page=-1", "query=협력 게임&size=0", "query=협력 게임&size=101")) {
			mockMvc.perform(get("/api/games/semantic-search?" + query))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		}

		verifyNoInteractions(semanticGameSearchQueryService);
	}

	@Test
	void T2_비로그인_playedFilter_요청은_401_UNAUTHENTICATED다() throws Exception {
		when(semanticGameSearchQueryService.search(any(SemanticGameSearchRequest.class),
			org.mockito.ArgumentMatchers.isNull()))
			.thenThrow(new UnauthenticatedException());

		mockMvc.perform(get("/api/games/semantic-search")
			.param("query", "협력 게임")
			.param("playedFilter", "PLAYED_ONLY"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
	}

	@Test
	void T3_정상_요청은_서비스_응답을_그대로_반환한다() throws Exception {
		when(semanticGameSearchQueryService.search(any(SemanticGameSearchRequest.class),
			org.mockito.ArgumentMatchers.isNull()))
			.thenReturn(new SemanticGameSearchResponse(List.of(), 0, 10, false, SemanticGameSearchMode.SEMANTIC));

		mockMvc.perform(get("/api/games/semantic-search").param("query", "협력 게임"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content").isEmpty())
			.andExpect(jsonPath("$.data.searchMode").value("SEMANTIC"));
	}

	@Test
	void T5_core가_UNAVAILABLE이면_503_SEARCH_UNAVAILABLE다() throws Exception {
		when(semanticGameSearchQueryService.search(any(SemanticGameSearchRequest.class),
			org.mockito.ArgumentMatchers.isNull()))
			.thenThrow(new BusinessException(ErrorCode.SEARCH_UNAVAILABLE));

		mockMvc.perform(get("/api/games/semantic-search").param("query", "협력 게임"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value(ErrorCode.SEARCH_UNAVAILABLE.getCode()));
	}

	@Test
	void T5_core가_LEXICAL_FALLBACK이면_200과_명시적_fallback_상태다() throws Exception {
		when(semanticGameSearchQueryService.search(any(SemanticGameSearchRequest.class),
			org.mockito.ArgumentMatchers.isNull()))
			.thenReturn(
				new SemanticGameSearchResponse(List.of(), 0, 10, false, SemanticGameSearchMode.LEXICAL_FALLBACK));

		mockMvc.perform(get("/api/games/semantic-search").param("query", "협력 게임"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.searchMode").value("LEXICAL_FALLBACK"));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestConfig {

		@Bean
		SemanticGameSearchQueryService semanticGameSearchQueryService() {
			return mock(SemanticGameSearchQueryService.class);
		}

		@Bean
		CurrentUserAccessor currentUserAccessor() {
			return mock(CurrentUserAccessor.class);
		}
	}
}
