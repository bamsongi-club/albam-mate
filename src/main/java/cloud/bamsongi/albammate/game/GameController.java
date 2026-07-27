package cloud.bamsongi.albammate.game;

import cloud.bamsongi.albammate.global.response.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games")
@Validated
public class GameController {

    private final GameListQueryService gameListQueryService;

    public GameController(GameListQueryService gameListQueryService) {
        this.gameListQueryService = gameListQueryService;
    }

    @GetMapping
    public ApiResponse<GamePageResponse> listGames(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        PageRequest pageable =
                PageRequest.of(page, size, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id")));
        return ApiResponse.success(
                HttpStatus.OK,
                GamePageResponse.from(gameListQueryService.findPage(keyword, pageable)));
    }
}
