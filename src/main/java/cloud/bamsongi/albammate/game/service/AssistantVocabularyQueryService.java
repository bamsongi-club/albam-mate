package cloud.bamsongi.albammate.game.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.contract.AssistantVocabularyQuery;
import cloud.bamsongi.albammate.game.repository.GameCategoryRepository;
import cloud.bamsongi.albammate.game.repository.GameMechanismRepository;
import cloud.bamsongi.albammate.game.repository.GameThemeRepository;
import lombok.RequiredArgsConstructor;

/**
 * 카탈로그 이름(한국어·영어)과 코드로 색인을 만들어 provider 레이블을 코드로 해석한다.
 *
 * 이름에는 축 이름이 접미사로 붙는 경우가 있어(예: 메커니즘 "협력 게임") 접미사를 뗀 형태도 함께 색인한다.
 * 해석하지 못한 레이블은 버리며, 이는 사용자 입력 오류가 아니라 provider 어휘와 카탈로그 어휘의 차이다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AssistantVocabularyQueryService implements AssistantVocabularyQuery {

	/** 이름 끝에 붙어 의미를 바꾸지 않는 축 접미사다. */
	private static final List<String> TRAILING_NOISE = List.of("게임", "game", "테마", "theme");

	private final GameCategoryRepository gameCategoryRepository;
	private final GameMechanismRepository gameMechanismRepository;
	private final GameThemeRepository gameThemeRepository;

	@Override
	public Resolved resolve(List<String> categoryLabels, List<String> mechanismLabels, List<String> themeLabels) {
		if (isEmpty(categoryLabels) && isEmpty(mechanismLabels) && isEmpty(themeLabels)) {
			return Resolved.empty();
		}
		Map<String, String> categories = categoryIndex();
		Map<String, String> mechanisms = mechanismIndex();
		Map<String, String> themes = themeIndex();

		// 서로 다른 레이블이 같은 코드로 해석될 수 있어 축마다 중복을 제거한다.
		LinkedHashSet<String> resolvedCategories = new LinkedHashSet<>();
		LinkedHashSet<String> resolvedMechanisms = new LinkedHashSet<>();
		LinkedHashSet<String> resolvedThemes = new LinkedHashSet<>();

		record Axis(List<String> labels, Map<String, String> preferred, LinkedHashSet<String> target) {
		}
		List<Axis> axes = List.of(
			new Axis(safe(categoryLabels), categories, resolvedCategories),
			new Axis(safe(mechanismLabels), mechanisms, resolvedMechanisms),
			new Axis(safe(themeLabels), themes, resolvedThemes));

		for (Axis axis : axes) {
			for (String label : axis.labels()) {
				String code = lookup(axis.preferred(), label);
				if (code != null) {
					axis.target().add(code);
					continue;
				}
				// provider가 축을 잘못 골랐을 수 있으므로 나머지 축에서도 같은 낱말을 찾는다.
				for (Axis fallback : axes) {
					if (fallback == axis) {
						continue;
					}
					String fallbackCode = lookup(fallback.preferred(), label);
					if (fallbackCode != null) {
						fallback.target().add(fallbackCode);
						break;
					}
				}
			}
		}
		return new Resolved(List.copyOf(resolvedCategories), List.copyOf(resolvedMechanisms),
			List.copyOf(resolvedThemes));
	}

	private Map<String, String> categoryIndex() {
		return index(gameCategoryRepository.findOptions().stream()
			.map(row -> new Entry(row.code(), row.nameKo(), row.nameEn()))
			.toList());
	}

	private Map<String, String> mechanismIndex() {
		return index(gameMechanismRepository.findPublicOptions().stream()
			.map(row -> new Entry(row.code(), row.nameKo(), row.nameEn()))
			.toList());
	}

	private Map<String, String> themeIndex() {
		return index(gameThemeRepository.findOptions().stream()
			.map(row -> new Entry(row.code(), row.nameKo(), row.nameEn()))
			.toList());
	}

	private String lookup(Map<String, String> index, String label) {
		String normalized = normalize(label);
		if (normalized.isEmpty()) {
			return null;
		}
		String code = index.get(normalized);
		return code != null ? code : index.get(stripTrailingNoise(normalized));
	}

	private Map<String, String> index(List<Entry> entries) {
		Map<String, String> index = new HashMap<>();
		// 먼저 넣은 항목을 유지해 표시 순서가 앞선 항목이 모호한 레이블을 가져가게 한다.
		for (Entry entry : entries) {
			for (String key : keysOf(entry)) {
				index.putIfAbsent(key, entry.code());
			}
		}
		return index;
	}

	private List<String> keysOf(Entry entry) {
		List<String> keys = new ArrayList<>();
		for (String source : List.of(entry.code(), entry.nameKo(), entry.nameEn())) {
			String normalized = normalize(source);
			if (normalized.isEmpty()) {
				continue;
			}
			keys.add(normalized);
			String stripped = stripTrailingNoise(normalized);
			if (!stripped.equals(normalized) && !stripped.isEmpty()) {
				keys.add(stripped);
			}
		}
		return keys;
	}

	/** 코드에만 붙는 BGG 식별자 접미사와 구분 문자를 제거해 이름·코드를 같은 평면에서 비교한다. */
	private String normalize(String value) {
		if (value == null) {
			return "";
		}
		String lowered = value.toLowerCase(Locale.ROOT).replaceAll("_bgg_\\d+$", "");
		StringBuilder builder = new StringBuilder(lowered.length());
		for (int index = 0; index < lowered.length(); index++) {
			char character = lowered.charAt(index);
			if (Character.isLetterOrDigit(character)) {
				builder.append(character);
			}
		}
		return builder.toString();
	}

	private String stripTrailingNoise(String normalized) {
		for (String noise : TRAILING_NOISE) {
			if (normalized.length() > noise.length() && normalized.endsWith(noise)) {
				return normalized.substring(0, normalized.length() - noise.length());
			}
		}
		return normalized;
	}

	private boolean isEmpty(List<String> labels) {
		return labels == null || labels.isEmpty();
	}

	private List<String> safe(List<String> labels) {
		return labels == null ? List.of() : labels;
	}

	private record Entry(String code, String nameKo, String nameEn) {
	}
}
