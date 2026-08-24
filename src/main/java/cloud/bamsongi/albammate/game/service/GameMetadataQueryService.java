package cloud.bamsongi.albammate.game.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.dto.GameCategoryOption;
import cloud.bamsongi.albammate.game.dto.GameMechanismOption;
import cloud.bamsongi.albammate.game.dto.GameThemeOption;
import cloud.bamsongi.albammate.game.repository.GameCategoryRepository;
import cloud.bamsongi.albammate.game.repository.GameMechanismRepository;
import cloud.bamsongi.albammate.game.repository.GameThemeRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 게임 카테고리·테마·메커니즘 선택지를 조회한다. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GameMetadataQueryService {
	@NonNull private final GameCategoryRepository gameCategoryRepository;
	@NonNull private final GameThemeRepository gameThemeRepository;
	@NonNull private final GameMechanismRepository gameMechanismRepository;

	public List<GameCategoryOption> findCategoryOptions() {
		return gameCategoryRepository.findOptions().stream().map(GameCategoryOption::from).toList();
	}

	public List<GameThemeOption> findThemeOptions() {
		return gameThemeRepository.findOptions().stream().map(GameThemeOption::from).toList();
	}

	public List<GameMechanismOption> findPublicMechanismOptions() {
		return gameMechanismRepository.findPublicOptions().stream()
			.map(GameMechanismOption::from)
			.toList();
	}
}
