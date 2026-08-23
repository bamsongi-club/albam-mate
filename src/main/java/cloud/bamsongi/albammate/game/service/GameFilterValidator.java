package cloud.bamsongi.albammate.game.service;

import java.util.List;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.game.repository.GameCategoryRepository;
import cloud.bamsongi.albammate.game.repository.GameMechanismRepository;
import cloud.bamsongi.albammate.game.repository.GameThemeRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 정규화한 게임 필터 code가 공개 카탈로그에 존재하는지 검증한다. */
@Component
@RequiredArgsConstructor
public class GameFilterValidator {

	@NonNull private final GameMechanismRepository gameMechanismRepository;
	@NonNull private final GameCategoryRepository gameCategoryRepository;
	@NonNull private final GameThemeRepository gameThemeRepository;

	public void validate(GameListSearchCriteria criteria) {
		validateCodes(criteria.getMechanisms(), gameMechanismRepository::countByCodeInAndIsPublicTrue);
		validateCodes(criteria.getCategories(), gameCategoryRepository::countByCodeIn);
		validateCodes(criteria.getThemes(), gameThemeRepository::countByCodeIn);
	}

	private void validateCodes(List<String> codes, Function<List<String>, Long> countByCodes) {
		if (!codes.isEmpty() && countByCodes.apply(codes) != codes.size()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
	}
}
