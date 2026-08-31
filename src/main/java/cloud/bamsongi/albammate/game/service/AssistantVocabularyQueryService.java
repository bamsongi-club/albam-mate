package cloud.bamsongi.albammate.game.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

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
 * 이름에는 축 이름이 접미사로 붙는 경우가 있어(예: 메커니즘 "협력 게임") 접미사를 뗀 형태도 함께 색인하되,
 * 정확한 이름과 같은 평면에 두지 않는다. 접미사를 뗀 별칭이 다른 항목의 정확한 이름을 가리면 안 되기 때문이다.
 * 해석하지 못한 레이블은 버리며, 이는 사용자 입력 오류가 아니라 provider 어휘와 카탈로그 어휘의 차이다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AssistantVocabularyQueryService implements AssistantVocabularyQuery {

	/** 이름 끝에 붙어 의미를 바꾸지 않는 축 접미사다. */
	private static final List<String> TRAILING_NOISE = List.of("게임", "game", "테마", "theme");

	/** 코드에만 붙는 BGG 식별자 접미사다. */
	private static final Pattern BGG_SUFFIX = Pattern.compile("_bgg_\\d+$");

	private final GameCategoryRepository gameCategoryRepository;
	private final GameMechanismRepository gameMechanismRepository;
	private final GameThemeRepository gameThemeRepository;

	@Override
	public Resolved resolve(List<String> categoryLabels, List<String> mechanismLabels, List<String> themeLabels) {
		if (isEmpty(categoryLabels) && isEmpty(mechanismLabels) && isEmpty(themeLabels)) {
			return Resolved.empty();
		}
		List<Axis> axes = List.of(
			new Axis(safe(categoryLabels), categoryIndex(), new LinkedHashSet<>()),
			new Axis(safe(mechanismLabels), mechanismIndex(), new LinkedHashSet<>()),
			new Axis(safe(themeLabels), themeIndex(), new LinkedHashSet<>()));

		for (Axis axis : axes) {
			for (String label : axis.labels()) {
				String normalized = normalize(label);
				if (normalized.isEmpty()) {
					continue;
				}
				// 일치 품질이 축 순서보다 앞선다. 다른 축의 정확한 이름이 이 축의 접미사 별칭보다 믿을 만하다.
				if (!place(axes, axis, normalized, true)
					&& !place(axes, axis, stripTrailingNoise(normalized), true)) {
					place(axes, axis, normalized, false);
				}
			}
		}
		// 서로 다른 레이블이 같은 코드로 해석될 수 있어 축마다 중복을 제거한다.
		return new Resolved(List.copyOf(axes.get(0).target()), List.copyOf(axes.get(1).target()),
			List.copyOf(axes.get(2).target()));
	}

	/** provider가 지정한 축을 먼저 보고, 없으면 나머지 축을 선언 순서대로 본다. 축을 잘못 골랐을 수 있어서다. */
	private boolean place(List<Axis> axes, Axis preferred, String key, boolean exactPlane) {
		if (add(preferred, key, exactPlane)) {
			return true;
		}
		for (Axis axis : axes) {
			if (axis != preferred && add(axis, key, exactPlane)) {
				return true;
			}
		}
		return false;
	}

	private boolean add(Axis axis, String key, boolean exactPlane) {
		String code = (exactPlane ? axis.index().exact() : axis.index().alias()).get(key);
		if (code == null) {
			return false;
		}
		axis.target().add(code);
		return true;
	}

	private Index categoryIndex() {
		return index(gameCategoryRepository.findOptions().stream()
			.map(row -> new Entry(row.code(), row.nameKo(), row.nameEn()))
			.toList());
	}

	private Index mechanismIndex() {
		return index(gameMechanismRepository.findPublicOptions().stream()
			.map(row -> new Entry(row.code(), row.nameKo(), row.nameEn()))
			.toList());
	}

	private Index themeIndex() {
		return index(gameThemeRepository.findOptions().stream()
			.map(row -> new Entry(row.code(), row.nameKo(), row.nameEn()))
			.toList());
	}

	private Index index(List<Entry> entries) {
		Map<String, String> exact = new HashMap<>();
		Map<String, String> alias = new HashMap<>();
		// 먼저 넣은 항목을 유지해 표시 순서가 앞선 항목이 모호한 레이블을 가져가게 한다.
		for (Entry entry : entries) {
			for (String key : namesOf(entry)) {
				exact.putIfAbsent(key, entry.code());
				String stripped = stripTrailingNoise(key);
				if (!stripped.equals(key)) {
					alias.putIfAbsent(stripped, entry.code());
				}
			}
		}
		return new Index(exact, alias);
	}

	private List<String> namesOf(Entry entry) {
		List<String> names = new ArrayList<>();
		for (String source : List.of(entry.code(), entry.nameKo(), entry.nameEn())) {
			String normalized = normalize(source);
			if (!normalized.isEmpty()) {
				names.add(normalized);
			}
		}
		return names;
	}

	/** 코드에만 붙는 BGG 식별자 접미사와 구분 문자를 제거해 이름·코드를 같은 평면에서 비교한다. 이름·코드는 모두 NOT NULL이다. */
	private String normalize(String value) {
		String lowered = BGG_SUFFIX.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("");
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

	private record Axis(List<String> labels, Index index, LinkedHashSet<String> target) {
	}

	/** 정확한 이름·코드 평면과 접미사를 뗀 별칭 평면을 분리해 둔다. */
	private record Index(Map<String, String> exact, Map<String, String> alias) {
	}

	private record Entry(String code, String nameKo, String nameEn) {
	}
}
