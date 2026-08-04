package cloud.bamsongi.albammate.game.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.repository.UserPlayedGameRepository;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;

@SpringBootTest
@AutoConfigureMockMvc
class UserPlayedGameHttpIntegrationTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private GameRepository gameRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private UserPlayedGameRepository userPlayedGameRepository;

	private final List<Long> gameIds = new ArrayList<>();
	private final List<Long> userIds = new ArrayList<>();

	@AfterEach
	void tearDown() {
		userIds.forEach(
			userId -> gameIds.forEach(gameId -> userPlayedGameRepository.deleteByUserIdAndGameId(userId, gameId)));
		gameIds.forEach(gameRepository::deleteById);
		userIds.forEach(userRepository::deleteById);
	}

	@Test
	void 등록과_취소는_현재_사용자_관계만_멱등하게_목표_상태로_수렴하고_표시시각을_노출하지_않는다() throws Exception {
		User user = user("state");
		Game game = game("State");

		for (int ignored = 0; ignored < 2; ignored++) {
			performWithCsrf(put(path(game)), user.getId(), true)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.gameId").value(game.getId()))
				.andExpect(jsonPath("$.data.playedByMe").value(true))
				.andExpect(jsonPath("$.data.createdAt").doesNotExist());
		}
		assertEquals(1, userPlayedGameRepository.findByUserIdAndGameId(user.getId(), game.getId()).size());

		for (int ignored = 0; ignored < 2; ignored++) {
			performWithCsrf(delete(path(game)), user.getId(), true)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.gameId").value(game.getId()))
				.andExpect(jsonPath("$.data.playedByMe").value(false))
				.andExpect(jsonPath("$.data.createdAt").doesNotExist());
		}
		assertTrue(userPlayedGameRepository.findByUserIdAndGameId(user.getId(), game.getId()).isEmpty());
	}

	@Test
	void 등록과_취소는_인증_CSRF_path_게임존재_순서의_실패에서_관계를_바꾸지_않는다() throws Exception {
		User user = user("priority");
		Game game = game("Priority");
		mark(user, game);

		for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.DELETE)) {
			mockMvc.perform(request(method, path(game)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
			assertEquals(1, userPlayedGameRepository.findByUserIdAndGameId(user.getId(), game.getId()).size());
			performWithCsrf(request(method, path(game)), user.getId(), false)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));
			assertEquals(1, userPlayedGameRepository.findByUserIdAndGameId(user.getId(), game.getId()).size());
		}

		for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.DELETE)) {
			mockMvc.perform(request(method, "/api/users/me/played-games/0"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
			performWithCsrf(request(method, "/api/users/me/played-games/0"), user.getId(), false)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));
			performWithCsrf(request(method, "/api/users/me/played-games/0"), user.getId(), true)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		}
		for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.DELETE)) {
			performWithCsrf(request(method, "/api/users/me/played-games/999999"), user.getId(), true)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value(ErrorCode.GAME_NOT_FOUND.getCode()));
		}
		assertEquals(1, userPlayedGameRepository.findByUserIdAndGameId(user.getId(), game.getId()).size());
	}

	@Test
	void 목록과_상세는_현재_사용자_관계만_playedByMe로_반환하고_관계필터를_다른조건과_AND로_적용한다() throws Exception {
		User userA = user("a");
		User userB = user("b");
		Game alpha = game("Alpha");
		Game beta = game("Beta");
		ReflectionTestUtils.setField(alpha, "minPlayers", 2);
		ReflectionTestUtils.setField(alpha, "maxPlayers", 4);
		ReflectionTestUtils.setField(alpha, "minPlayTimeMinutes", 1);
		ReflectionTestUtils.setField(alpha, "maxPlayTimeMinutes", 30);
		ReflectionTestUtils.setField(alpha, "complexity", new BigDecimal("2.50"));
		gameRepository.saveAndFlush(alpha);
		ReflectionTestUtils.setField(beta, "minPlayers", 2);
		ReflectionTestUtils.setField(beta, "maxPlayers", 4);
		ReflectionTestUtils.setField(beta, "minPlayTimeMinutes", 1);
		ReflectionTestUtils.setField(beta, "maxPlayTimeMinutes", 30);
		ReflectionTestUtils.setField(beta, "complexity", new BigDecimal("2.50"));
		gameRepository.saveAndFlush(beta);
		mark(userA, alpha);

		mockMvc
			.perform(get("/api/games").param("keyword", "PlayedGameHttp-Alpha").with(authenticationFor(userA.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content[0].playedByMe").value(true));
		mockMvc.perform(get("/api/games/{gameId}", alpha.getId()).with(authenticationFor(userB.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.playedByMe").value(false));
		mockMvc.perform(
			get("/api/games")
				.param("keyword", "PlayedGameHttp")
				.param("playedFilter", "PLAYED_ONLY")
				.with(authenticationFor(userB.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(0));
		mockMvc.perform(get("/api/games/{gameId}", alpha.getId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.playedByMe").value((Object)null));
		mockMvc.perform(get("/api/games").param("keyword", "PlayedGameHttp").with(authenticationFor(userA.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(2))
			.andExpect(jsonPath("$.data.page").value(0))
			.andExpect(jsonPath("$.data.size").value(10))
			.andExpect(jsonPath("$.data.totalPages").value(1))
			.andExpect(jsonPath("$.data.content[0].id").value(alpha.getId()))
			.andExpect(jsonPath("$.data.content[0].playedByMe").value(true))
			.andExpect(jsonPath("$.data.content[1].id").value(beta.getId()))
			.andExpect(jsonPath("$.data.content[1].playedByMe").value(false));

		mockMvc.perform(
			get("/api/games")
				.param("keyword", "PlayedGameHttp")
				.param("playerCount", "2")
				.param("complexityMin", "2.00")
				.param("complexityMax", "3.00")
				.param("playedFilter", "PLAYED_ONLY")
				.with(authenticationFor(userA.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.content[0].id").value(alpha.getId()));
		mockMvc.perform(
			get("/api/games")
				.param("keyword", "PlayedGameHttp")
				.param("playedFilter", "EXCLUDE_PLAYED")
				.with(authenticationFor(userA.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.content[0].id").value(beta.getId()))
			.andExpect(jsonPath("$.data.content[0].playedByMe").value(false));

		assertTrue(userPlayedGameRepository.findByUserIdAndGameId(userA.getId(), beta.getId()).isEmpty());
		assertTrue(userPlayedGameRepository.findByUserIdAndGameId(userB.getId(), alpha.getId()).isEmpty());
	}

	@Test
	void 한사용자의_취소는_같은게임을_표시한_다른사용자의_관계와_표시값을_바꾸지_않는다() throws Exception {
		User userA = user("delete-a");
		User userB = user("delete-b");
		Game game = game("Shared");
		mark(userA, game);
		mark(userB, game);

		performWithCsrf(delete(path(game)), userA.getId(), true)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.playedByMe").value(false));

		assertTrue(userPlayedGameRepository.findByUserIdAndGameId(userA.getId(), game.getId()).isEmpty());
		assertEquals(1, userPlayedGameRepository.findByUserIdAndGameId(userB.getId(), game.getId()).size());
		mockMvc.perform(get("/api/games/{gameId}", game.getId()).with(authenticationFor(userB.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.playedByMe").value(true));
	}

	@Test
	void 관계필터의_잘못된값과_중복은_인증보다먼저_검증하고_생략한_목록은_공개동작을_유지한다() throws Exception {
		Game game = game("Filter");

		for (String query : List.of("playedFilter=UNKNOWN", "playedFilter=PLAYED_ONLY&playedFilter=EXCLUDE_PLAYED")) {
			mockMvc.perform(get("/api/games?" + query))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		}
		for (String value : List.of("PLAYED_ONLY", "EXCLUDE_PLAYED")) {
			mockMvc.perform(get("/api/games").param("playedFilter", value))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
		}
		mockMvc.perform(get("/api/games").param("keyword", "PlayedGameHttp-Filter"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.content[0].id").value(game.getId()))
			.andExpect(jsonPath("$.data.content[0].playedByMe").value((Object)null));
		mockMvc.perform(get("/api/users/me/played-games").with(authenticationFor(user("no-get").getId())))
			.andExpect(status().isNotFound());
	}

	private void mark(User user, Game game) throws Exception {
		performWithCsrf(put(path(game)), user.getId(), true)
			.andExpect(status().isOk());
	}

	private ResultActions performWithCsrf(MockHttpServletRequestBuilder request, long userId, boolean valid)
		throws Exception {
		CsrfContext csrfContext = csrfContext(userId);
		return mockMvc.perform(
			request.cookie(csrfContext.csrfCookie(), csrfContext.sessionCookie())
				.header("X-XSRF-TOKEN", valid ? csrfContext.csrfCookie().getValue() : "invalid"));
	}

	// 인증은 첫 요청에서만 후처리기로 만들고 이후에는 세션 쿠키로 잇는다. Spring Session이 관리하는 세션은
	// MockHttpSession을 주입해도 쓰이지 않아 SecurityContext와 CSRF 토큰이 유실된다.
	private CsrfContext csrfContext(long userId) throws Exception {
		MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf").with(authenticationFor(userId)))
			.andExpect(status().isOk())
			.andReturn();
		Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
		assertNotNull(csrfCookie);
		Cookie sessionCookie = csrfResult.getResponse().getCookie("JSESSIONID");
		assertNotNull(sessionCookie);
		return new CsrfContext(csrfCookie, sessionCookie);
	}

	private String path(Game game) {
		return "/api/users/me/played-games/" + game.getId();
	}

	private User user(String suffix) {
		User user = userRepository.saveAndFlush(
			User.create("played-game-" + suffix + "@example.com", "{bcrypt}hash", "사용자" + suffix));
		userIds.add(user.getId());
		return user;
	}

	private Game game(String suffix) {
		Game game = new Game(
			900_000L + gameIds.size(),
			"PlayedGameHttp-" + suffix,
			"Played game " + suffix,
			"2~4명",
			"전략",
			"30분",
			"설명",
			"상세 설명");
		game = gameRepository.saveAndFlush(game);
		gameIds.add(game.getId());
		return game;
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor authenticationFor(long userId) {
		return authentication(
			UsernamePasswordAuthenticationToken.authenticated(
				new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
	}

	private record CsrfContext(Cookie csrfCookie, Cookie sessionCookie) {
	}
}
