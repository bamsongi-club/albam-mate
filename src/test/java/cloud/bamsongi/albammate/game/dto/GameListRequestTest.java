package cloud.bamsongi.albammate.game.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

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
		valid.setPlayTime(List.of(GamePlayTimeFilter.OVER_20_TO_30, GamePlayTimeFilter.AT_LEAST_90));
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

	@Test
	void 인원_범위와_전용_인원_조건은_각각_유효한_입력만_허용한다() {
		GameListRequest range = new GameListRequest();
		range.setPlayerCountMin(2);
		range.setPlayerCountMax(4);
		range.setPlayerCountExact(true);
		GameListRequest exclusiveOnly = new GameListRequest();
		exclusiveOnly.setExclusivePlayerCount(List.of(1, 2));
		GameListRequest minOnly = new GameListRequest();
		minOnly.setPlayerCountMin(3);
		GameListRequest exactWithoutBoundary = new GameListRequest();
		exactWithoutBoundary.setPlayerCountExact(true);

		assertTrue(validator.validate(range).isEmpty());
		assertTrue(validator.validate(exclusiveOnly).isEmpty());
		assertTrue(validator.validate(minOnly).isEmpty());
		assertTrue(validator.validate(exactWithoutBoundary).isEmpty());
	}

	@Test
	void 인원_범위와_전용_인원을_함께_전달하거나_경계가_잘못되면_거절한다() {
		GameListRequest rangeWithExclusive = new GameListRequest();
		rangeWithExclusive.setPlayerCountMin(2);
		rangeWithExclusive.setExclusivePlayerCount(List.of(1));
		GameListRequest maxWithExclusive = new GameListRequest();
		maxWithExclusive.setPlayerCountMax(4);
		maxWithExclusive.setExclusivePlayerCount(List.of(2));
		GameListRequest reversedRange = new GameListRequest();
		reversedRange.setPlayerCountMin(5);
		reversedRange.setPlayerCountMax(4);
		GameListRequest zeroMin = new GameListRequest();
		zeroMin.setPlayerCountMin(0);
		GameListRequest unsupportedExclusive = new GameListRequest();
		unsupportedExclusive.setExclusivePlayerCount(List.of(3));

		assertFalse(validator.validate(rangeWithExclusive).isEmpty());
		assertFalse(validator.validate(maxWithExclusive).isEmpty());
		assertFalse(validator.validate(reversedRange).isEmpty());
		assertFalse(validator.validate(zeroMin).isEmpty());
		assertFalse(validator.validate(unsupportedExclusive).isEmpty());
	}

	@Test
	void 빈_값만_담긴_전용_인원은_인원_범위와_충돌하지_않는다() {
		GameListRequest request = new GameListRequest();
		request.setPlayerCountMin(2);
		request.setPlayerCountMax(4);
		request.setExclusivePlayerCount(Collections.singletonList(null));

		assertTrue(validator.validate(request).isEmpty());
	}

	@Test
	void 해본게임_필터는_단일_비null값만_조회와_검증에_사용한다() {
		GameListRequest missing = new GameListRequest();
		GameListRequest empty = new GameListRequest();
		empty.setPlayedFilter(List.of());
		GameListRequest nullValue = new GameListRequest();
		nullValue.setPlayedFilter(Collections.singletonList(null));
		GameListRequest duplicate = new GameListRequest();
		duplicate.setPlayedFilter(List.of(PlayedFilter.PLAYED_ONLY, PlayedFilter.EXCLUDE_PLAYED));
		GameListRequest single = new GameListRequest();
		single.setPlayedFilter(List.of(PlayedFilter.PLAYED_ONLY));

		assertNull(missing.getPlayedFilter());
		assertNull(empty.getPlayedFilter());
		assertNull(nullValue.getPlayedFilter());
		assertNull(duplicate.getPlayedFilter());
		assertEquals(PlayedFilter.PLAYED_ONLY, single.getPlayedFilter());
		assertTrue(validator.validate(missing).isEmpty());
		assertFalse(validator.validate(empty).isEmpty());
		assertFalse(validator.validate(nullValue).isEmpty());
		assertFalse(validator.validate(duplicate).isEmpty());
		assertTrue(validator.validate(single).isEmpty());
	}

	@Test
	void 메타데이터_코드와_인원_themeMatch를_검증하고_기본_ANY를_유지한다() {
		GameListRequest valid = new GameListRequest();
		valid.setCategory(List.of("STRATEGY"));
		valid.setTheme(List.of("FANTASY"));
		valid.setRecommendedPlayerCount(List.of(3));
		assertEquals(ThemeMatch.ANY, valid.getThemeMatch());
		assertTrue(validator.validate(valid).isEmpty());
		GameListRequest invalid = new GameListRequest();
		invalid.setCategory(List.of("strategy", ""));
		invalid.setRecommendedPlayerCount(List.of(0));
		invalid.setThemeMatch(List.of(ThemeMatch.ANY, ThemeMatch.ALL));
		assertFalse(validator.validate(invalid).isEmpty());
	}

	@Test
	void 빈_themeMatch는_기본_ANY로_조회된다() {
		GameListRequest request = new GameListRequest();
		request.setThemeMatch(List.of());

		assertEquals(ThemeMatch.ANY, request.getThemeMatch());
	}

	@Test
	void null을_담은_단일_themeMatch는_검증오류로_거절한다() {
		GameListRequest request = new GameListRequest();
		request.setThemeMatch(Collections.singletonList(null));

		assertFalse(validator.validate(request).isEmpty());
	}

	@Test
	void mechanismMatch는_누락_빈값_null_중복_단일_ANY_ALL을_구분해_검증한다() {
		GameListRequest missing = new GameListRequest();
		GameListRequest empty = new GameListRequest();
		empty.setMechanismMatch(List.of());
		GameListRequest nullValue = new GameListRequest();
		nullValue.setMechanismMatch(Collections.singletonList(null));
		GameListRequest duplicate = new GameListRequest();
		duplicate.setMechanismMatch(List.of(MechanismMatch.ANY, MechanismMatch.ALL));
		GameListRequest singleAny = new GameListRequest();
		singleAny.setMechanismMatch(List.of(MechanismMatch.ANY));
		GameListRequest singleAll = new GameListRequest();
		singleAll.setMechanismMatch(List.of(MechanismMatch.ALL));

		assertEquals(MechanismMatch.ANY, missing.getMechanismMatch());
		assertEquals(MechanismMatch.ANY, empty.getMechanismMatch());
		assertNull(nullValue.getMechanismMatch());
		assertEquals(MechanismMatch.ANY, duplicate.getMechanismMatch());
		assertEquals(MechanismMatch.ANY, singleAny.getMechanismMatch());
		assertEquals(MechanismMatch.ALL, singleAll.getMechanismMatch());
		assertTrue(validator.validate(missing).isEmpty());
		assertFalse(validator.validate(empty).isEmpty());
		assertFalse(validator.validate(nullValue).isEmpty());
		assertFalse(validator.validate(duplicate).isEmpty());
		assertTrue(validator.validate(singleAny).isEmpty());
		assertTrue(validator.validate(singleAll).isEmpty());
	}
}
