package cloud.bamsongi.albammate.game.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.game.dto.PlayedGameStateResponse;
import cloud.bamsongi.albammate.game.service.UserPlayedGameService;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import jakarta.validation.constraints.Min;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/users/me/played-games")
@RequiredArgsConstructor
public class UserPlayedGameController {

	@NonNull private final UserPlayedGameService userPlayedGameService;
	@NonNull private final CurrentUserAccessor currentUserAccessor;

	@PutMapping("/{gameId}")
	public ApiResponse<PlayedGameStateResponse> markPlayed(@PathVariable @Min(1) Long gameId) {
		return ApiResponse.success(
			HttpStatus.OK,
			userPlayedGameService.markPlayed(currentUserAccessor.requireCurrentUserId(), gameId));
	}

	@DeleteMapping("/{gameId}")
	public ApiResponse<PlayedGameStateResponse> unmarkPlayed(@PathVariable @Min(1) Long gameId) {
		return ApiResponse.success(
			HttpStatus.OK,
			userPlayedGameService.unmarkPlayed(currentUserAccessor.requireCurrentUserId(), gameId));
	}
}
