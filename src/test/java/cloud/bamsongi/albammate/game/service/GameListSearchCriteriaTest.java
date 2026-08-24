package cloud.bamsongi.albammate.game.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.dto.GamePlayTimeFilter;
import cloud.bamsongi.albammate.game.dto.PlayedFilter;

class GameListSearchCriteriaTest {

	@Test
	void 조건이_전혀_없는_요청은_필터_없음으로_판정한다() {
		assertTrue(GameListSearchCriteria.from(new GameListRequest()).isFilterless());
	}

	@Test
	void 검색어가_있으면_필터_없음이_아니다() {
		GameListRequest request = new GameListRequest();
		request.setKeyword("카탄");

		assertFalse(GameListSearchCriteria.from(request).isFilterless());
	}

	@Test
	void 예정_모임만_보기를_켜면_필터_없음이_아니다() {
		GameListRequest request = new GameListRequest();
		request.setUpcomingOnly(true);

		assertFalse(GameListSearchCriteria.from(request).isFilterless());
	}

	@Test
	void 인원수가_있으면_필터_없음이_아니다() {
		GameListRequest request = new GameListRequest();
		request.setPlayerCount(4);

		assertFalse(GameListSearchCriteria.from(request).isFilterless());
	}

	@Test
	void 최소_인원이_있으면_필터_없음이_아니다() {
		GameListRequest request = new GameListRequest();
		request.setPlayerCountMin(2);

		assertFalse(GameListSearchCriteria.from(request).isFilterless());
	}

	@Test
	void 최대_인원이_있으면_필터_없음이_아니다() {
		GameListRequest request = new GameListRequest();
		request.setPlayerCountMax(4);

		assertFalse(GameListSearchCriteria.from(request).isFilterless());
	}

	@Test
	void 인원_정확히_일치를_켜면_필터_없음이_아니다() {
		GameListRequest request = new GameListRequest();
		request.setPlayerCountExact(true);

		assertFalse(GameListSearchCriteria.from(request).isFilterless());
	}

	@Test
	void 전용_인원이_있으면_필터_없음이_아니다() {
		GameListRequest request = new GameListRequest();
		request.setExclusivePlayerCount(List.of(2));

		assertFalse(GameListSearchCriteria.from(request).isFilterless());
	}

	@Test
	void 플레이시간_구간이_있으면_필터_없음이_아니다() {
		GameListRequest request = new GameListRequest();
		request.setPlayTime(List.of(GamePlayTimeFilter.UP_TO_10));

		assertFalse(GameListSearchCriteria.from(request).isFilterless());
	}

	@Test
	void 최연소_참여자_나이가_있으면_필터_없음이_아니다() {
		GameListRequest request = new GameListRequest();
		request.setYoungestPlayerAge(10);

		assertFalse(GameListSearchCriteria.from(request).isFilterless());
	}

	@Test
	void 복잡도_최소가_있으면_필터_없음이_아니다() {
		GameListRequest request = new GameListRequest();
		request.setComplexityMin(new BigDecimal("2.00"));

		assertFalse(GameListSearchCriteria.from(request).isFilterless());
	}

	@Test
	void 복잡도_최대가_있으면_필터_없음이_아니다() {
		GameListRequest request = new GameListRequest();
		request.setComplexityMax(new BigDecimal("4.00"));

		assertFalse(GameListSearchCriteria.from(request).isFilterless());
	}

	@Test
	void 해본_게임_조건이_있으면_필터_없음이_아니다() {
		GameListRequest request = new GameListRequest();
		request.setPlayedFilter(List.of(PlayedFilter.PLAYED_ONLY));

		assertFalse(GameListSearchCriteria.from(request).isFilterless());
	}

	@Test
	void 메커니즘이_있으면_필터_없음이_아니다() {
		GameListRequest request = new GameListRequest();
		request.setMechanism(List.of("DRAFTING"));

		assertFalse(GameListSearchCriteria.from(request).isFilterless());
	}

	@Test
	void 카테고리가_있으면_필터_없음이_아니다() {
		GameListRequest request = new GameListRequest();
		request.setCategory(List.of("STRATEGY"));

		assertFalse(GameListSearchCriteria.from(request).isFilterless());
	}

	@Test
	void 테마가_있으면_필터_없음이_아니다() {
		GameListRequest request = new GameListRequest();
		request.setTheme(List.of("FANTASY"));

		assertFalse(GameListSearchCriteria.from(request).isFilterless());
	}

	@Test
	void 추천_인원이_있으면_필터_없음이_아니다() {
		GameListRequest request = new GameListRequest();
		request.setRecommendedPlayerCount(List.of(4));

		assertFalse(GameListSearchCriteria.from(request).isFilterless());
	}

	@Test
	void 베스트_인원이_있으면_필터_없음이_아니다() {
		GameListRequest request = new GameListRequest();
		request.setBestPlayerCount(List.of(4));

		assertFalse(GameListSearchCriteria.from(request).isFilterless());
	}
}
