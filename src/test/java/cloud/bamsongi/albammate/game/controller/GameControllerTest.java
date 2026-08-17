package cloud.bamsongi.albammate.game.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.game.dto.GameDetail;
import cloud.bamsongi.albammate.game.dto.GameListItem;
import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.dto.GamePlayTimeFilter;
import cloud.bamsongi.albammate.game.fixture.GameDetailFixture;
import cloud.bamsongi.albammate.game.fixture.GameListItemFixture;
import cloud.bamsongi.albammate.game.service.GameDetailQueryService;
import cloud.bamsongi.albammate.game.service.GameQueryService;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;

@WebMvcTest(controllers = GameController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, GameControllerTest.GameControllerTestConfiguration.class})
class GameControllerTest {

	@Autowired
	private GameQueryService gameQueryService;

	@Autowired
	private GameDetailQueryService gameDetailQueryService;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private CurrentUserAccessor currentUserAccessor;

	@BeforeEach
	void setUp() {
		reset(gameQueryService);
		reset(gameDetailQueryService);
		reset(currentUserAccessor);
		when(currentUserAccessor.currentUserId()).thenReturn(Optional.empty());
	}

	@Test
	void 공개_게임_목록은_페이지와_게임_카드_필드를_반환한다() throws Exception {
		GameListItem item = GameListItemFixture.of(
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
		when(gameQueryService.findPage(any(GameListRequest.class), any()))
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
		when(gameQueryService.findPage(any(GameListRequest.class), any()))
			.thenReturn(new PageImpl<>(List.of(), pageable, 3));

		mockMvc.perform(get("/api/games?keyword=Catan&page=1&size=1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content").isEmpty())
			.andExpect(jsonPath("$.data.page").value(1))
			.andExpect(jsonPath("$.data.size").value(1))
			.andExpect(jsonPath("$.data.totalElements").value(3))
			.andExpect(jsonPath("$.data.totalPages").value(3))
			.andExpect(jsonPath("$.data.hasNext").value(true));

		GameListRequest request = capturedListRequest();
		org.junit.jupiter.api.Assertions.assertEquals("Catan", request.getKeyword());
		org.junit.jupiter.api.Assertions.assertFalse(request.isUpcomingOnly());
		org.junit.jupiter.api.Assertions.assertEquals(1, request.getPage());
		org.junit.jupiter.api.Assertions.assertEquals(1, request.getSize());
	}

	@Test
	void 예정_모임_필터_true를_서비스에_전달한다() throws Exception {
		PageRequest pageable = PageRequest.of(0, 10);
		when(gameQueryService.findPage(any(GameListRequest.class), any()))
			.thenReturn(new PageImpl<>(List.of(), pageable, 0));

		mockMvc.perform(get("/api/games?upcomingOnly=true"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content").isEmpty());

		org.junit.jupiter.api.Assertions.assertTrue(capturedListRequest().isUpcomingOnly());
	}

	@Test
	void 예정_모임_필터_false를_서비스에_전달한다() throws Exception {
		PageRequest pageable = PageRequest.of(0, 10);
		when(gameQueryService.findPage(any(GameListRequest.class), any()))
			.thenReturn(new PageImpl<>(List.of(), pageable, 0));

		mockMvc.perform(get("/api/games?upcomingOnly=false"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content").isEmpty());

		org.junit.jupiter.api.Assertions.assertFalse(capturedListRequest().isUpcomingOnly());
	}

	@Test
	void size_상한_100은_성공한다() throws Exception {
		PageRequest pageable = PageRequest.of(0, 100);
		when(gameQueryService.findPage(any(GameListRequest.class), any()))
			.thenReturn(new PageImpl<>(List.of(), pageable, 0));

		mockMvc.perform(get("/api/games?size=100"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.size").value(100))
			.andExpect(jsonPath("$.data.content").isEmpty());

		org.junit.jupiter.api.Assertions.assertEquals(100, capturedListRequest().getSize());
	}

	@Test
	void 페이지_파라미터가_계약_범위를_벗어나면_VALIDATION_ERROR다() throws Exception {
		for (String query : List.of(
			"page=-1", "size=0", "size=101", "page=not-a-number", "size=not-a-number", "upcomingOnly=not-a-boolean",
			"playerCount=0", "playerCount=11", "playTime=INVALID", "complexityMin=0.99", "complexityMax=5.01",
			"complexityMin=3.00&complexityMax=2.00",
			"playTime=SHORT", "playTime=MEDIUM", "playTime=LONG", "playTime=UP_TO_10&playTime=SHORT",
			"playerCountMin=0", "playerCountMax=0", "playerCountMin=5&playerCountMax=4",
			"playerCountMin=not-a-number", "exclusivePlayerCount=3", "exclusivePlayerCount=0",
			"playerCountMin=2&exclusivePlayerCount=1", "playerCountMax=4&exclusivePlayerCount=2")) {
			mockMvc.perform(get("/api/games?" + query))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		}
	}

	@Test
	void 빈_목록_parameter는_기본값으로_서비스에_전달한다() throws Exception {
		PageRequest pageable = PageRequest.of(0, 10);
		when(gameQueryService.findPage(any(GameListRequest.class), any()))
			.thenReturn(new PageImpl<>(List.of(), pageable, 0));

		mockMvc.perform(get("/api/games?page=&size=&upcomingOnly="))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.page").value(0))
			.andExpect(jsonPath("$.data.size").value(10));

		GameListRequest request = capturedListRequest();
		org.junit.jupiter.api.Assertions.assertEquals(0, request.getPage());
		org.junit.jupiter.api.Assertions.assertEquals(10, request.getSize());
		org.junit.jupiter.api.Assertions.assertFalse(request.isUpcomingOnly());
	}

	@Test
	void 게임_조건_파라미터를_서비스에_전달한다() throws Exception {
		when(gameQueryService.findPage(any(GameListRequest.class), any()))
			.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

		mockMvc.perform(
			get("/api/games?playerCount=10&playTime=UP_TO_10&playTime=AT_LEAST_90"
				+ "&complexityMin=2.00&complexityMax=3.00"))
			.andExpect(status().isOk());

		GameListRequest request = capturedListRequest();
		org.junit.jupiter.api.Assertions.assertEquals(10, request.getPlayerCount());
		org.junit.jupiter.api.Assertions.assertEquals(
			List.of(GamePlayTimeFilter.UP_TO_10, GamePlayTimeFilter.AT_LEAST_90),
			request.getPlayTime());
		org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("2.00"), request.getComplexityMin());
		org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("3.00"), request.getComplexityMax());
	}

	@Test
	void 최연소_참여자_나이를_서비스에_전달한다() throws Exception {
		when(gameQueryService.findPage(any(GameListRequest.class), any()))
			.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

		mockMvc.perform(get("/api/games?youngestPlayerAge=10"))
			.andExpect(status().isOk());

		org.junit.jupiter.api.Assertions.assertEquals(10, capturedListRequest().getYoungestPlayerAge());
	}

	@Test
	void 최연소_참여자_나이가_0_음수_정수가_아니면_조회_없이_VALIDATION_ERROR다() throws Exception {
		for (String value : List.of("0", "-1", "not-a-number")) {
			mockMvc.perform(get("/api/games?youngestPlayerAge=" + value))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
			verifyNoInteractions(gameQueryService);
			reset(gameQueryService);
		}
	}

	@Test
	void 인원_범위_조건_파라미터를_서비스에_전달한다() throws Exception {
		when(gameQueryService.findPage(any(GameListRequest.class), any()))
			.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

		mockMvc.perform(get("/api/games?playerCountMin=2&playerCountMax=4&playerCountExact=true"))
			.andExpect(status().isOk());

		GameListRequest request = capturedListRequest();
		org.junit.jupiter.api.Assertions.assertEquals(2, request.getPlayerCountMin());
		org.junit.jupiter.api.Assertions.assertEquals(4, request.getPlayerCountMax());
		org.junit.jupiter.api.Assertions.assertTrue(request.isPlayerCountExact());
	}

	@Test
	void 반복_전달한_전용_인원을_목록으로_바인딩한다() throws Exception {
		when(gameQueryService.findPage(any(GameListRequest.class), any()))
			.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

		mockMvc.perform(get("/api/games?exclusivePlayerCount=1&exclusivePlayerCount=2"))
			.andExpect(status().isOk());

		org.junit.jupiter.api.Assertions.assertEquals(
			List.of(1, 2), capturedListRequest().getExclusivePlayerCount());
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
		GameDetail detail = GameDetailFixture.of(
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
		when(gameDetailQueryService.findById(1L, null)).thenReturn(detail);

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

		verify(gameDetailQueryService).findById(1L, null);
	}

	@Test
	void 없는_게임_상세는_GAME_NOT_FOUND다() throws Exception {
		when(gameDetailQueryService.findById(999L, null))
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

	@Test
	void 공개_게임_목록_성공은_검색어_없이_허용된_업무결과_로그만_남긴다() throws Exception {
		when(gameQueryService.findPage(any(GameListRequest.class), any()))
			.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(GameController.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);

		try {
			mockMvc.perform(get("/api/games?keyword=secret-search-term"))
				.andExpect(status().isOk());

			ILoggingEvent event = appender.list.stream()
				.filter(value -> value.getKeyValuePairs().stream()
					.anyMatch(pair -> pair.key.equals("event") && pair.value.equals("game_search_completed")))
				.findFirst()
				.orElseThrow();
			java.util.Map<String, Object> fields = event.getKeyValuePairs().stream()
				.collect(java.util.stream.Collectors.toMap(pair -> pair.key, pair -> pair.value));
			org.junit.jupiter.api.Assertions.assertEquals("success", fields.get("outcome"));
			org.junit.jupiter.api.Assertions.assertEquals(0, fields.get("resultCount"));
			org.junit.jupiter.api.Assertions.assertTrue(((Long)fields.get("durationMs")) >= 0);
			org.junit.jupiter.api.Assertions.assertFalse(event.getFormattedMessage().contains("secret-search-term"));
			org.junit.jupiter.api.Assertions.assertFalse(event.getFormattedMessage().contains("keyword="));
			org.junit.jupiter.api.Assertions.assertFalse(event.getFormattedMessage().contains("userId="));
			org.junit.jupiter.api.Assertions.assertFalse(event.getFormattedMessage().contains("session"));
			org.junit.jupiter.api.Assertions.assertFalse(event.getFormattedMessage().contains("token"));
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	void 공개_게임_목록_기술실패는_500_응답과_ERROR_실패이벤트를_남긴다() throws Exception {
		when(gameQueryService.findPage(any(GameListRequest.class), any()))
			.thenThrow(new IllegalStateException("search failure"));
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);

		try {
			mockMvc.perform(get("/api/games"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value(ErrorCode.INTERNAL_SERVER_ERROR.getCode()));

			ILoggingEvent failure = appender.list.stream()
				.filter(event -> hasKeyValue(event, "event", "game_search_failed"))
				.findFirst()
				.orElseThrow();
			org.junit.jupiter.api.Assertions.assertEquals(ch.qos.logback.classic.Level.ERROR, failure.getLevel());
			java.util.Map<String, Object> fields = keyValues(failure);
			org.junit.jupiter.api.Assertions.assertEquals("failed", fields.get("outcome"));
			org.junit.jupiter.api.Assertions.assertEquals(ErrorCode.INTERNAL_SERVER_ERROR.getCode(),
				fields.get("failureCode"));
			org.junit.jupiter.api.Assertions.assertEquals("java.lang.IllegalStateException",
				fields.get("exceptionClass"));
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	void 비정상_게임_ID_상세요청은_gameId_없이_INFO_실패이벤트를_남긴다() throws Exception {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);

		try {
			for (String gameId : List.of("0", "-1", "not-a-number")) {
				mockMvc.perform(get("/api/games/" + gameId))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
			}

			List<ILoggingEvent> failures = appender.list.stream()
				.filter(event -> hasKeyValue(event, "event", "game_detail_failed"))
				.toList();
			org.junit.jupiter.api.Assertions.assertEquals(3, failures.size());
			org.junit.jupiter.api.Assertions.assertTrue(
				failures.stream().allMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.INFO));
			org.junit.jupiter.api.Assertions.assertTrue(
				failures.stream().allMatch(event -> "rejected".equals(keyValues(event).get("outcome"))
					&& ErrorCode.VALIDATION_ERROR.getCode().equals(keyValues(event).get("failureCode"))));
			org.junit.jupiter.api.Assertions
				.assertTrue(failures.stream().noneMatch(event -> keyValues(event).containsKey("gameId")));
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	void 게임_상세_성공과_미존재는_응답을유지하며_허용된_결과와_실패코드로_기록한다() throws Exception {
		GameDetail detail = GameDetailFixture.of(
			1L, 1001L, "카탄", "Catan", null, "3~4명", "전략", "60~90분", new BigDecimal("2.00"),
			0L, "카탄 기본판", "설명", "상세 설명");
		when(gameDetailQueryService.findById(1L, null)).thenReturn(detail);
		when(gameDetailQueryService.findById(999L, null))
			.thenThrow(new BusinessException(ErrorCode.GAME_NOT_FOUND));
		Logger controllerLogger = (Logger)org.slf4j.LoggerFactory.getLogger(GameController.class);
		Logger exceptionLogger = (Logger)org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);
		ListAppender<ILoggingEvent> controllerAppender = new ListAppender<>();
		ListAppender<ILoggingEvent> exceptionAppender = new ListAppender<>();
		controllerAppender.start();
		exceptionAppender.start();
		controllerLogger.addAppender(controllerAppender);
		exceptionLogger.addAppender(exceptionAppender);

		try {
			mockMvc.perform(get("/api/games/1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.id").value(1));
			mockMvc.perform(get("/api/games/999"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value(ErrorCode.GAME_NOT_FOUND.getCode()));

			ILoggingEvent completed = controllerAppender.list.stream()
				.filter(event -> hasKeyValue(event, "event", "game_detail_completed"))
				.findFirst()
				.orElseThrow();
			ILoggingEvent failed = exceptionAppender.list.stream()
				.filter(event -> hasKeyValue(event, "event", "game_detail_failed"))
				.findFirst()
				.orElseThrow();
			org.junit.jupiter.api.Assertions.assertEquals(1L, keyValues(completed).get("gameId"));
			org.junit.jupiter.api.Assertions.assertEquals("success", keyValues(completed).get("outcome"));
			org.junit.jupiter.api.Assertions.assertEquals(999L, keyValues(failed).get("gameId"));
			org.junit.jupiter.api.Assertions.assertEquals("rejected", keyValues(failed).get("outcome"));
			org.junit.jupiter.api.Assertions.assertEquals(ErrorCode.GAME_NOT_FOUND.getCode(),
				keyValues(failed).get("failureCode"));
		} finally {
			controllerLogger.detachAppender(controllerAppender);
			exceptionLogger.detachAppender(exceptionAppender);
			controllerAppender.stop();
			exceptionAppender.stop();
		}
	}

	private GameListRequest capturedListRequest() {
		ArgumentCaptor<GameListRequest> requestCaptor = ArgumentCaptor.forClass(GameListRequest.class);
		verify(gameQueryService).findPage(requestCaptor.capture(), org.mockito.ArgumentMatchers.isNull());
		return requestCaptor.getValue();
	}

	private boolean hasKeyValue(ILoggingEvent event, String key, Object value) {
		return event.getKeyValuePairs().stream().anyMatch(pair -> pair.key.equals(key) && pair.value.equals(value));
	}

	private java.util.Map<String, Object> keyValues(ILoggingEvent event) {
		return event.getKeyValuePairs().stream()
			.collect(java.util.stream.Collectors.toMap(pair -> pair.key, pair -> pair.value));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class GameControllerTestConfiguration {

		@Bean
		GameQueryService gameQueryService() {
			return mock(GameQueryService.class);
		}

		@Bean
		GameDetailQueryService gameDetailQueryService() {
			return mock(GameDetailQueryService.class);
		}

		@Bean
		CurrentUserAccessor currentUserAccessor() {
			return mock(CurrentUserAccessor.class);
		}
	}
}
