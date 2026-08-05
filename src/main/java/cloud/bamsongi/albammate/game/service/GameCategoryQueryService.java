package cloud.bamsongi.albammate.game.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.dto.GameCategoryOption;
import cloud.bamsongi.albammate.game.repository.GameCategoryRepository;
import lombok.*;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GameCategoryQueryService {
	@NonNull private final GameCategoryRepository gameCategoryRepository;

	public List<GameCategoryOption> findOptions() {
		return gameCategoryRepository.findOptions().stream().map(GameCategoryOption::from).toList();
	}
}
