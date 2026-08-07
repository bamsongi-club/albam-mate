package cloud.bamsongi.albammate.game.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import cloud.bamsongi.albammate.game.dto.GameMechanismOption;
import cloud.bamsongi.albammate.game.service.GameMechanismQueryService;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;

@WebMvcTest(controllers = GameMechanismController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, GameMechanismControllerTest.FixtureConfiguration.class})
class GameMechanismControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private GameMechanismQueryService gameMechanismQueryService;

	@Test
	void 공개_메커니즘_선택지는_응답_계약_필드만_반환한다() throws Exception {
		when(gameMechanismQueryService.findPublicOptions()).thenReturn(
			List.of(new GameMechanismOption("HAND_MANAGEMENT", "핸드 관리", "Hand Management", 1, "손에 든 카드를 관리하는 메커니즘입니다.")));

		mockMvc.perform(get("/api/game-mechanisms"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data[0].code").value("HAND_MANAGEMENT"))
			.andExpect(jsonPath("$.data[0].nameKo").value("핸드 관리"))
			.andExpect(jsonPath("$.data[0].nameEn").value("Hand Management"))
			.andExpect(jsonPath("$.data[0].featuredOrder").value(1))
			.andExpect(jsonPath("$.data[0].descriptionKo").value("손에 든 카드를 관리하는 메커니즘입니다."))
			.andExpect(jsonPath("$.data[0].id").doesNotExist())
			.andExpect(jsonPath("$.data[0].bggMechanismId").doesNotExist())
			.andExpect(jsonPath("$.data[0].reviewedBy").doesNotExist());
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixtureConfiguration {

		@Bean
		GameMechanismQueryService gameMechanismQueryService() {
			return mock(GameMechanismQueryService.class);
		}
	}
}
