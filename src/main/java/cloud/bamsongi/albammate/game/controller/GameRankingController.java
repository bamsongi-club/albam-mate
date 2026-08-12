package cloud.bamsongi.albammate.game.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.game.dto.GameRankingResponse;
import cloud.bamsongi.albammate.game.service.GameRankingQueryService;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/game-rankings")
@RequiredArgsConstructor
public class GameRankingController {
	@NonNull private final GameRankingQueryService gameRankingQueryService;

	@GetMapping
	public ApiResponse<GameRankingResponse> findRankings() {
		return ApiResponse.success(HttpStatus.OK, gameRankingQueryService.findRankings());
	}
}
