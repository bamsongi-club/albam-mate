package cloud.bamsongi.albammate.game.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class GameListRequestTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void 누락하거나_빈_페이지_파라미터는_기본값을_유지한다() {
		GameListRequest request = new GameListRequest();
		request.setPage(null);
		request.setSize(null);
		request.setUpcomingOnly(null);

		assertEquals(0, request.getPage());
		assertEquals(10, request.getSize());
		assertFalse(request.isUpcomingOnly());
		assertTrue(validator.validate(request).isEmpty());
	}

	@Test
	void 페이지와_예정_모임_필터의_유효한_경계를_허용한다() {
		GameListRequest request = new GameListRequest();
		request.setKeyword("카탄");
		request.setUpcomingOnly(true);
		request.setPage(0);
		request.setSize(100);

		assertEquals("카탄", request.getKeyword());
		assertTrue(request.isUpcomingOnly());
		assertTrue(validator.validate(request).isEmpty());
	}

	@Test
	void 페이지와_크기_범위를_벗어나면_거절한다() {
		GameListRequest negativePage = new GameListRequest();
		negativePage.setPage(-1);
		GameListRequest zeroSize = new GameListRequest();
		zeroSize.setSize(0);
		GameListRequest oversize = new GameListRequest();
		oversize.setSize(101);

		assertFalse(validator.validate(negativePage).isEmpty());
		assertFalse(validator.validate(zeroSize).isEmpty());
		assertFalse(validator.validate(oversize).isEmpty());
	}

	@Test
	void 검색_수치_조건의_유효_범위와_닫힌_복잡도_구간을_검증한다() {
		GameListRequest valid = new GameListRequest();
		valid.setPlayerCount(10);
		valid.setPlayTime(GamePlayTimeFilter.MEDIUM);
		valid.setComplexityMin(new BigDecimal("1.00"));
		valid.setComplexityMax(new BigDecimal("5.00"));

		GameListRequest invalidPlayerCount = new GameListRequest();
		invalidPlayerCount.setPlayerCount(0);
		GameListRequest invalidComplexity = new GameListRequest();
		invalidComplexity.setComplexityMin(new BigDecimal("0.99"));
		GameListRequest reversedComplexity = new GameListRequest();
		reversedComplexity.setComplexityMin(new BigDecimal("3.00"));
		reversedComplexity.setComplexityMax(new BigDecimal("2.00"));

		assertTrue(validator.validate(valid).isEmpty());
		assertFalse(validator.validate(invalidPlayerCount).isEmpty());
		assertFalse(validator.validate(invalidComplexity).isEmpty());
		assertFalse(validator.validate(reversedComplexity).isEmpty());
	}
}
