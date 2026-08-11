package cloud.bamsongi.albammate.game.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.dto.GameThemeOption;
import cloud.bamsongi.albammate.game.repository.GameThemeRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 게임 테마 선택지를 조회한다. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GameThemeQueryService {
	@NonNull private final GameThemeRepository gameThemeRepository;

	public List<GameThemeOption> findOptions() {
		return gameThemeRepository.findOptions().stream().map(GameThemeOption::from).toList();
	}
}
