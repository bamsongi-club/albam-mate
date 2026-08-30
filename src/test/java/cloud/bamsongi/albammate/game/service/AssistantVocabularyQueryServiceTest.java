package cloud.bamsongi.albammate.game.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.game.contract.AssistantVocabularyQuery;
import cloud.bamsongi.albammate.game.repository.GameCategoryOptionRow;
import cloud.bamsongi.albammate.game.repository.GameCategoryRepository;
import cloud.bamsongi.albammate.game.repository.GameMechanismOptionRow;
import cloud.bamsongi.albammate.game.repository.GameMechanismRepository;
import cloud.bamsongi.albammate.game.repository.GameThemeOptionRow;
import cloud.bamsongi.albammate.game.repository.GameThemeRepository;

class AssistantVocabularyQueryServiceTest {

	@Test
	void 지정한_축에서_레이블을_카탈로그_코드로_해석한다() {
		var resolved = service().resolve(List.of("전략"), List.of("드래프팅"), List.of("공포"));

		assertEquals(List.of("STRATEGY"), resolved.categories());
		assertEquals(List.of("DRAFTING"), resolved.mechanisms());
		assertEquals(List.of("HORROR_BGG_1024"), resolved.themes());
	}

	@Test
	void 코드의_BGG_접미사와_이름의_축_접미사를_무시하고_찾는다() {
		var resolved = service().resolve(List.of(), List.of("협력", "협력 게임"), List.of("우주", "HORROR_BGG_1024"));

		// 서로 다른 레이블이 같은 코드로 해석되면 축마다 한 번만 남는다.
		assertEquals(List.of("COOPERATIVE"), resolved.mechanisms());
		assertEquals(List.of("SPACE", "HORROR_BGG_1024"), resolved.themes());
	}

	@Test
	void 축을_잘못_지정한_레이블도_실제로_그_낱말을_가진_축에_배치한다() {
		// "협력"은 category가 아니라 mechanism이다.
		var resolved = service().resolve(List.of("협력"), List.of("공포"), List.of("전략"));

		assertEquals(List.of("STRATEGY"), resolved.categories());
		assertEquals(List.of("COOPERATIVE"), resolved.mechanisms());
		assertEquals(List.of("HORROR_BGG_1024"), resolved.themes());
	}

	@Test
	void 카탈로그에_없는_레이블은_요청_실패가_아니라_조건에서_버린다() {
		// "게임"은 접미사와 길이가 같아 떼어낼 수 없고, "!!!"는 정규화하면 빈 문자열이 된다.
		// 세 레이블 모두 버려지고, theme 축에 잘못 들어온 "전략"만 category로 옮겨진다.
		var resolved = service().resolve(List.of("없는낱말", "!!!", "게임"), List.of(), List.of("전략"));

		assertEquals(List.of("STRATEGY"), resolved.categories());
		assertEquals(List.of(), resolved.mechanisms());
		assertEquals(List.of(), resolved.themes());
	}

	@Test
	void 같은_이름이_여러_항목에_있으면_먼저_조회된_항목이_레이블을_가져간다() {
		var resolved = service().resolve(List.of("전략", "Strategy"), List.of(), List.of());

		assertEquals(List.of("STRATEGY"), resolved.categories());
	}

	@Test
	void 축이_null이어도_나머지_축을_해석한다() {
		var resolved = service().resolve(null, List.of("드래프팅"), null);

		assertEquals(List.of(), resolved.categories());
		assertEquals(List.of("DRAFTING"), resolved.mechanisms());
		assertEquals(List.of(), resolved.themes());
	}

	@Test
	void theme_축에만_레이블이_있어도_해석한다() {
		var resolved = service().resolve(List.of(), List.of(), List.of("공포"));

		assertEquals(List.of("HORROR_BGG_1024"), resolved.themes());
	}

	@Test
	void 레이블에_붙은_축_접미사를_떼어_정확한_이름과_맞춘다() {
		// 카탈로그의 정확한 이름은 "전략"이고 레이블만 "전략 게임"이다.
		var resolved = service().resolve(List.of("전략 게임"), List.of(), List.of());

		assertEquals(List.of("STRATEGY"), resolved.categories());
	}

	@Test
	void 다른_축의_정확한_이름이_이_축의_접미사_별칭보다_우선한다() {
		GameCategoryRepository categoryRepository = mock(GameCategoryRepository.class);
		GameMechanismRepository mechanismRepository = mock(GameMechanismRepository.class);
		GameThemeRepository themeRepository = mock(GameThemeRepository.class);
		// category "협력 게임"은 별칭 "협력"을, mechanism "협력"은 정확한 이름 "협력"을 갖는다.
		when(categoryRepository.findOptions()).thenReturn(List.of(
			new GameCategoryOptionRow("COOP_CATEGORY", "협력 게임", "Cooperative Games", 1)));
		when(mechanismRepository.findPublicOptions()).thenReturn(List.of(
			new GameMechanismOptionRow("COOPERATIVE", "협력", "Cooperative", 1, null)));
		when(themeRepository.findOptions()).thenReturn(List.of());
		var service = new AssistantVocabularyQueryService(categoryRepository, mechanismRepository,
			themeRepository);

		var resolved = service.resolve(List.of("협력"), List.of(), List.of());

		assertEquals(List.of(), resolved.categories());
		assertEquals(List.of("COOPERATIVE"), resolved.mechanisms());
	}

	@Test
	void 해석할_레이블이_하나도_없으면_카탈로그를_조회하지_않는다() {
		GameCategoryRepository categoryRepository = mock(GameCategoryRepository.class);
		GameMechanismRepository mechanismRepository = mock(GameMechanismRepository.class);
		GameThemeRepository themeRepository = mock(GameThemeRepository.class);
		var service = new AssistantVocabularyQueryService(categoryRepository, mechanismRepository, themeRepository);

		assertEquals(AssistantVocabularyQuery.Resolved.empty(), service.resolve(List.of(), List.of(), List.of()));
		assertEquals(AssistantVocabularyQuery.Resolved.empty(), service.resolve(null, null, null));

		org.mockito.Mockito.verifyNoInteractions(categoryRepository, mechanismRepository, themeRepository);
	}

	@Test
	void Resolved는_null_목록을_빈_목록으로_보관한다() {
		var resolved = new AssistantVocabularyQuery.Resolved(null, null, null);

		assertEquals(List.of(), resolved.categories());
		assertEquals(List.of(), resolved.mechanisms());
		assertEquals(List.of(), resolved.themes());
	}

	private AssistantVocabularyQueryService service() {
		GameCategoryRepository categoryRepository = mock(GameCategoryRepository.class);
		GameMechanismRepository mechanismRepository = mock(GameMechanismRepository.class);
		GameThemeRepository themeRepository = mock(GameThemeRepository.class);
		when(categoryRepository.findOptions()).thenReturn(List.of(
			new GameCategoryOptionRow("STRATEGY", "전략", "Strategy", 1),
			// 같은 이름을 가진 뒤 항목은 색인을 덮어쓰지 않는다.
			new GameCategoryOptionRow("STRATEGY_ALIAS", "전략", "Strategy Alias", 2),
			// 이름이 정규화하면 빈 문자열이 되는 항목은 색인에 넣지 않는다.
			new GameCategoryOptionRow("PUNCTUATION_ONLY", "!!!", "!!!", 3)));
		when(mechanismRepository.findPublicOptions()).thenReturn(List.of(
			new GameMechanismOptionRow("COOPERATIVE", "협력 게임", "Cooperative", 1, null),
			new GameMechanismOptionRow("DRAFTING", "드래프팅", "Drafting", 2, null)));
		when(themeRepository.findOptions()).thenReturn(List.of(
			new GameThemeOptionRow("HORROR_BGG_1024", "공포", "Horror"),
			new GameThemeOptionRow("SPACE", "우주 테마", "Space")));
		return new AssistantVocabularyQueryService(categoryRepository, mechanismRepository, themeRepository);
	}
}
