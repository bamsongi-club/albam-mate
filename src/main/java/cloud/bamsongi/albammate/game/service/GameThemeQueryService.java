package cloud.bamsongi.albammate.game.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.dto.GameThemeOption;
import cloud.bamsongi.albammate.game.repository.GameThemeRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GameThemeQueryService {
	@NonNull private final GameThemeRepository gameThemeRepository;

	public List<GameThemeOption> findOptions() {
		return gameThemeRepository.findOptions().stream().map(GameThemeOption::from).toList();
	}
}
