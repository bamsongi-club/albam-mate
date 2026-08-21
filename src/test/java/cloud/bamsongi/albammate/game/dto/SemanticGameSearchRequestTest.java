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

class SemanticGameSearchRequestTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	private SemanticGameSearchRequest request(String query) {
		SemanticGameSearchRequest request = new SemanticGameSearchRequest();
		request.setQuery(query);
		return request;
	}

	@Test
	void 누락하거나_빈_페이지_파라미터는_기본값을_유지한다() {
		SemanticGameSearchRequest request = request("협력 게임");
		request.setPage(null);
		request.setSize(null);

		assertEquals(0, request.getPage());
		assertEquals(10, request.getSize());
		assertTrue(validator.validate(request).isEmpty());
	}

	@Test
	void 페이지와_크기의_유효한_경계를_허용한다() {
		SemanticGameSearchRequest request = request("협력 게임");
		request.setPage(0);
		request.setSize(100);

		assertEquals(0, request.getPage());
		assertEquals(100, request.getSize());
		assertTrue(validator.validate(request).isEmpty());
	}

	@Test
	void 페이지와_크기_범위를_벗어나면_거절한다() {
		SemanticGameSearchRequest negativePage = request("협력 게임");
		negativePage.setPage(-1);
		SemanticGameSearchRequest zeroSize = request("협력 게임");
		zeroSize.setSize(0);
		SemanticGameSearchRequest oversize = request("협력 게임");
		oversize.setSize(101);

		assertFalse(validator.validate(negativePage).isEmpty());
		assertFalse(validator.validate(zeroSize).isEmpty());
		assertFalse(validator.validate(oversize).isEmpty());
	}

	@Test
	void 빈_query는_검증_오류다() {
		assertFalse(validator.validate(request("")).isEmpty());
	}

	@Test
	void 최대_길이_이하_query는_허용하고_초과하면_거절한다() {
		SemanticGameSearchRequest exact = request("가".repeat(SemanticGameSearchRequest.MAX_QUERY_LENGTH));
		SemanticGameSearchRequest tooLong = request("가".repeat(SemanticGameSearchRequest.MAX_QUERY_LENGTH + 1));

		assertTrue(exact.isQueryLengthValid());
		assertTrue(validator.validate(exact).isEmpty());
		assertFalse(tooLong.isQueryLengthValid());
		assertFalse(validator.validate(tooLong).isEmpty());
	}

	@Test
	void 복잡도_구간은_닫힌_범위와_역전을_구분해_검증한다() {
		SemanticGameSearchRequest valid = request("협력 게임");
		valid.setComplexityMin(new BigDecimal("1.00"));
		valid.setComplexityMax(new BigDecimal("5.00"));
		SemanticGameSearchRequest reversed = request("협력 게임");
		reversed.setComplexityMin(new BigDecimal("3.00"));
		reversed.setComplexityMax(new BigDecimal("2.00"));
		SemanticGameSearchRequest outOfRange = request("협력 게임");
		outOfRange.setComplexityMin(new BigDecimal("0.99"));

		assertTrue(valid.isComplexityRangeValid());
		assertTrue(validator.validate(valid).isEmpty());
		assertFalse(reversed.isComplexityRangeValid());
		assertFalse(validator.validate(reversed).isEmpty());
		assertFalse(validator.validate(outOfRange).isEmpty());
	}

	@Test
	void 인원_범위와_전용_인원_조건은_각각_유효한_입력만_허용한다() {
		SemanticGameSearchRequest range = request("협력 게임");
		range.setPlayerCountMin(2);
		range.setPlayerCountMax(4);
		SemanticGameSearchRequest exclusiveOnly = request("협력 게임");
		exclusiveOnly.setExclusivePlayerCount(List.of(1, 2));
		SemanticGameSearchRequest minOnly = request("협력 게임");
		minOnly.setPlayerCountMin(3);
		SemanticGameSearchRequest maxOnly = request("협력 게임");
		maxOnly.setPlayerCountMax(6);

		assertTrue(range.isPlayerCountRangeValid());
		assertTrue(range.isPlayerCountConditionExclusive());
		assertTrue(validator.validate(range).isEmpty());
		assertTrue(validator.validate(exclusiveOnly).isEmpty());
		assertTrue(validator.validate(minOnly).isEmpty());
		assertTrue(maxOnly.isPlayerCountConditionExclusive());
		assertTrue(validator.validate(maxOnly).isEmpty());
	}

	@Test
	void 인원_범위와_전용_인원을_함께_전달하거나_경계가_잘못되면_거절한다() {
		SemanticGameSearchRequest rangeWithExclusive = request("협력 게임");
		rangeWithExclusive.setPlayerCountMin(2);
		rangeWithExclusive.setExclusivePlayerCount(List.of(1));
		SemanticGameSearchRequest reversedRange = request("협력 게임");
		reversedRange.setPlayerCountMin(5);
		reversedRange.setPlayerCountMax(4);

		assertFalse(rangeWithExclusive.isPlayerCountConditionExclusive());
		assertFalse(validator.validate(rangeWithExclusive).isEmpty());
		assertFalse(reversedRange.isPlayerCountRangeValid());
		assertFalse(validator.validate(reversedRange).isEmpty());
	}

	@Test
	void 빈_값만_담긴_전용_인원은_인원_범위와_충돌하지_않는다() {
		SemanticGameSearchRequest request = request("협력 게임");
		request.setPlayerCountMin(2);
		request.setPlayerCountMax(4);
		request.setExclusivePlayerCount(Collections.singletonList(null));

		assertTrue(request.isPlayerCountConditionExclusive());
		assertTrue(validator.validate(request).isEmpty());
	}

	@Test
	void 해본게임_필터는_단일_비null값만_조회와_검증에_사용한다() {
		SemanticGameSearchRequest missing = request("협력 게임");
		SemanticGameSearchRequest empty = request("협력 게임");
		empty.setPlayedFilter(List.of());
		SemanticGameSearchRequest nullValue = request("협력 게임");
		nullValue.setPlayedFilter(Collections.singletonList(null));
		SemanticGameSearchRequest duplicate = request("협력 게임");
		duplicate.setPlayedFilter(List.of(PlayedFilter.PLAYED_ONLY, PlayedFilter.EXCLUDE_PLAYED));
		SemanticGameSearchRequest single = request("협력 게임");
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
	void themeMatch를_전달하지_않으면_기본_ANY로_조회된다() {
		SemanticGameSearchRequest request = request("협력 게임");

		assertEquals(ThemeMatch.ANY, request.getThemeMatch());
		assertTrue(validator.validate(request).isEmpty());
	}

	@Test
	void 빈_themeMatch는_기본_ANY로_조회되고_null을_담은_단일값은_거절한다() {
		SemanticGameSearchRequest empty = request("협력 게임");
		empty.setThemeMatch(List.of());
		SemanticGameSearchRequest nullValue = request("협력 게임");
		nullValue.setThemeMatch(Collections.singletonList(null));
		SemanticGameSearchRequest single = request("협력 게임");
		single.setThemeMatch(List.of(ThemeMatch.ALL));

		assertEquals(ThemeMatch.ANY, empty.getThemeMatch());
		assertFalse(validator.validate(empty).isEmpty());
		assertFalse(validator.validate(nullValue).isEmpty());
		assertEquals(ThemeMatch.ALL, single.getThemeMatch());
		assertTrue(validator.validate(single).isEmpty());
	}

	@Test
	void mechanismMatch는_누락_빈값_null_중복_단일_ANY_ALL을_구분해_검증한다() {
		SemanticGameSearchRequest missing = request("협력 게임");
		SemanticGameSearchRequest empty = request("협력 게임");
		empty.setMechanismMatch(List.of());
		SemanticGameSearchRequest nullValue = request("협력 게임");
		nullValue.setMechanismMatch(Collections.singletonList(null));
		SemanticGameSearchRequest duplicate = request("협력 게임");
		duplicate.setMechanismMatch(List.of(MechanismMatch.ANY, MechanismMatch.ALL));
		SemanticGameSearchRequest singleAny = request("협력 게임");
		singleAny.setMechanismMatch(List.of(MechanismMatch.ANY));
		SemanticGameSearchRequest singleAll = request("협력 게임");
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

	@Test
	void 검색_조건만_기존_GameListRequest_경로에_전달하고_keyword는_비운다() {
		SemanticGameSearchRequest request = request("가족과 짧게 할 협력 게임");
		request.setPlayerCountMin(2);
		request.setPlayerCountMax(4);
		request.setMechanism(List.of("COOPERATIVE_GAME"));
		request.setPlayedFilter(List.of(PlayedFilter.PLAYED_ONLY));

		GameListRequest gameListRequest = request.toGameListRequest();

		assertNull(gameListRequest.getKeyword());
		assertEquals(2, gameListRequest.getPlayerCountMin());
		assertEquals(4, gameListRequest.getPlayerCountMax());
		assertEquals(List.of("COOPERATIVE_GAME"), gameListRequest.getMechanism());
		assertEquals(PlayedFilter.PLAYED_ONLY, gameListRequest.getPlayedFilter());
	}
}
