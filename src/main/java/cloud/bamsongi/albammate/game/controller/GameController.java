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
import cloud.bamsongi.albammate.game.service.GameDetailQueryService;
import cloud.bamsongi.albammate.game.service.GameQueryService;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.response.PageResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/games")
@RequiredArgsConstructor
@Slf4j
public class GameController {

	@NonNull private final GameQueryService gameQueryService;
	@NonNull private final GameDetailQueryService gameDetailQueryService;
	@NonNull private final CurrentUserAccessor currentUserAccessor;

	@GetMapping
	public ApiResponse<PageResponse<GameListItem>> listGames(
		@Valid @ModelAttribute
		GameListRequest request) {
		long startedAt = System.nanoTime();
		PageResponse<GameListItem> page = PageResponse.from(
			gameQueryService.findPage(request, currentUserAccessor.currentUserId().orElse(null)));
		log.atInfo()
			.addKeyValue("event", "game_search_completed")
			.addKeyValue("outcome", "success")
			.addKeyValue("resultCount", page.content().size())
			.addKeyValue("durationMs", (java.lang.System.nanoTime() - startedAt) / 1_000_000)
			.log("game search completed");
		return ApiResponse.success(
			HttpStatus.OK,
			page);
	}

	@GetMapping("/{gameId}")
	public ApiResponse<GameDetail> getGameDetail(@PathVariable @Min(1) Long gameId) {
		long startedAt = System.nanoTime();
		GameDetail detail = gameDetailQueryService.findById(gameId, currentUserAccessor.currentUserId().orElse(null));
		log.atInfo()
			.addKeyValue("event", "game_detail_completed")
			.addKeyValue("gameId", gameId)
			.addKeyValue("outcome", "success")
			.addKeyValue("durationMs", (java.lang.System.nanoTime() - startedAt) / 1_000_000)
			.log("game detail completed");
		return ApiResponse.success(
			HttpStatus.OK,
			detail);
	}
}
