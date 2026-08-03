package cloud.bamsongi.albammate.game.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.game.dto.GameDetail;
import cloud.bamsongi.albammate.game.dto.GameListItem;
import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.service.GameQueryService;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.response.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/games")
@RequiredArgsConstructor
public class GameController {

	@NonNull private final GameQueryService gameQueryService;

	@GetMapping
	public ApiResponse<PageResponse<GameListItem>> listGames(
		@Valid @ModelAttribute
		GameListRequest request) {
		return ApiResponse.success(
			HttpStatus.OK,
			PageResponse.from(gameQueryService.findPage(request)));
	}

	@GetMapping("/{gameId}")
	public ApiResponse<GameDetail> getGameDetail(@PathVariable @Min(1) Long gameId) {
		return ApiResponse.success(HttpStatus.OK, gameQueryService.findById(gameId));
	}
}
