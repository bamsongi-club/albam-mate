package cloud.bamsongi.albammate.game.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import cloud.bamsongi.albammate.game.dto.GameCategoryOption;
import cloud.bamsongi.albammate.game.service.GameCategoryQueryService;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import lombok.*;

@RestController
@RequestMapping("/api/game-categories")
@RequiredArgsConstructor
public class GameCategoryController {
	@NonNull private final GameCategoryQueryService gameCategoryQueryService;

	@GetMapping
	public ApiResponse<List<GameCategoryOption>> listGameCategories() {
		return ApiResponse.success(HttpStatus.OK, gameCategoryQueryService.findOptions());
	}
}
