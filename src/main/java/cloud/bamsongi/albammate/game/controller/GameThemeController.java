package cloud.bamsongi.albammate.game.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.game.dto.GameThemeOption;
import cloud.bamsongi.albammate.game.service.GameThemeQueryService;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/game-themes")
@RequiredArgsConstructor
public class GameThemeController {
	@NonNull private final GameThemeQueryService gameThemeQueryService;

	@GetMapping
	public ApiResponse<List<GameThemeOption>> listGameThemes() {
		return ApiResponse.success(HttpStatus.OK, gameThemeQueryService.findOptions());
	}
}
