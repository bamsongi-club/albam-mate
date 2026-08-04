package cloud.bamsongi.albammate.game.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.game.dto.GameMechanismOption;
import cloud.bamsongi.albammate.game.service.GameMechanismQueryService;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/game-mechanisms")
@RequiredArgsConstructor
public class GameMechanismController {

	@NonNull private final GameMechanismQueryService gameMechanismQueryService;

	@GetMapping
	public ApiResponse<List<GameMechanismOption>> listGameMechanisms() {
		return ApiResponse.success(HttpStatus.OK, gameMechanismQueryService.findPublicOptions());
	}
}
