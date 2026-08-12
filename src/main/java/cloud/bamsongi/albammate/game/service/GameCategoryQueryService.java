package cloud.bamsongi.albammate.game.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.dto.GameCategoryOption;
import cloud.bamsongi.albammate.game.repository.GameCategoryRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 게임 카테고리 선택지를 조회한다. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GameCategoryQueryService {
	@NonNull private final GameCategoryRepository gameCategoryRepository;

	public List<GameCategoryOption> findOptions() {
		return gameCategoryRepository.findOptions().stream().map(GameCategoryOption::from).toList();
	}
}
