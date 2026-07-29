package cloud.bamsongi.albammate.game.controller;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.game.dto.GameDetail;
import cloud.bamsongi.albammate.game.dto.GameListItem;
import cloud.bamsongi.albammate.game.service.GameDetailQueryService;
import cloud.bamsongi.albammate.game.service.GameListQueryService;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.response.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/games")
@Validated
@RequiredArgsConstructor
public class GameController {

	private final GameListQueryService gameListQueryService;
	private final GameDetailQueryService gameDetailQueryService;

	@GetMapping
	public ApiResponse<PageResponse<GameListItem>> listGames(
		@RequestParam(required = false)
		String keyword,
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
		PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id")));
		return ApiResponse.success(
			HttpStatus.OK, PageResponse.from(gameListQueryService.findPage(keyword, pageable)));
	}

	@GetMapping("/{gameId}")
	public ApiResponse<GameDetail> getGameDetail(@PathVariable @Min(1) Long gameId) {
		return ApiResponse.success(HttpStatus.OK, gameDetailQueryService.findById(gameId));
	}
}
