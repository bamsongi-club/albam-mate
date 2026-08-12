package cloud.bamsongi.albammate.game.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.game.dto.GameCategoryOption;
import cloud.bamsongi.albammate.game.dto.GameMechanismOption;
import cloud.bamsongi.albammate.game.dto.GameThemeOption;
import cloud.bamsongi.albammate.game.service.GameMetadataQueryService;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class GameMetadataController {
	@NonNull private final GameMetadataQueryService gameMetadataQueryService;

	@GetMapping("/api/game-categories")
	public ApiResponse<List<GameCategoryOption>> listGameCategories() {
		return ApiResponse.success(HttpStatus.OK, gameMetadataQueryService.findCategoryOptions());
	}

	@GetMapping("/api/game-themes")
	public ApiResponse<List<GameThemeOption>> listGameThemes() {
		return ApiResponse.success(HttpStatus.OK, gameMetadataQueryService.findThemeOptions());
	}

	@GetMapping("/api/game-mechanisms")
	public ApiResponse<List<GameMechanismOption>> listGameMechanisms() {
		return ApiResponse.success(HttpStatus.OK, gameMetadataQueryService.findPublicMechanismOptions());
	}
}
