package cloud.bamsongi.albammate.matching.controller;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.response.PageResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import cloud.bamsongi.albammate.matching.dto.MatchBlockListItemResponse;
import cloud.bamsongi.albammate.matching.service.command.MatchBlockCommandService;
import cloud.bamsongi.albammate.matching.service.query.MatchBlockQueryService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

/** 로그인 사용자의 MATCH 차단 목록·차단·차단 해제 HTTP 경계다. */
@RestController
@Validated
@RequestMapping("/api/matches")
@RequiredArgsConstructor
public class MatchBlockController {

	private final MatchBlockQueryService matchBlockQueryService;
	private final MatchBlockCommandService matchBlockCommandService;
	private final CurrentUserAccessor currentUserAccessor;

	@GetMapping(path = "/blocks", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<PageResponse<MatchBlockListItemResponse>>> list(
		@RequestParam(defaultValue = "0") @Min(0) int page,
		@RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
		return ResponseEntity.ok(ApiResponse.success(
			HttpStatus.OK, matchBlockQueryService.findPage(currentUserAccessor.requireCurrentUserId(), page, size)));
	}

	@PutMapping(path = "/parties/{partyId}/participants/{participantRef}/block", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<MatchBlockListItemResponse>> block(
		@PathVariable @Positive long partyId, @PathVariable
		UUID participantRef) {
		MatchBlockListItemResponse response = matchBlockCommandService.block(
			currentUserAccessor.requireCurrentUserId(), partyId, participantRef);
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, response));
	}

	@DeleteMapping(path = "/blocks/{blockId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<Map<String, Object>>> unblock(@PathVariable @Positive long blockId) {
		matchBlockCommandService.unblock(currentUserAccessor.requireCurrentUserId(), blockId);
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK));
	}
}
