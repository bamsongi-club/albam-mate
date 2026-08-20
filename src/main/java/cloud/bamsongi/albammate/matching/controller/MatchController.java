package cloud.bamsongi.albammate.matching.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import cloud.bamsongi.albammate.matching.dto.CurrentMatchStateResponse;
import cloud.bamsongi.albammate.matching.dto.MatchProposalResponseRequest;
import cloud.bamsongi.albammate.matching.dto.MatchRequestCreateRequest;
import cloud.bamsongi.albammate.matching.service.command.MatchPartyLeaveService;
import cloud.bamsongi.albammate.matching.service.command.MatchProposalResponseService;
import cloud.bamsongi.albammate.matching.service.command.MatchRequestCommandService;
import cloud.bamsongi.albammate.matching.service.query.MatchCurrentStateQueryCoordinator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

@Validated
@RestController
@RequestMapping("/api/matches")
public class MatchController {

	private final MatchCurrentStateQueryCoordinator currentStateQueryCoordinator;
	private final MatchRequestCommandService matchRequestCommandService;
	private final MatchProposalResponseService matchProposalResponseService;
	private final MatchPartyLeaveService matchPartyLeaveService;
	private final CurrentUserAccessor currentUserAccessor;

	public MatchController(
		MatchCurrentStateQueryCoordinator currentStateQueryCoordinator,
		MatchRequestCommandService matchRequestCommandService,
		MatchProposalResponseService matchProposalResponseService,
		MatchPartyLeaveService matchPartyLeaveService,
		CurrentUserAccessor currentUserAccessor) {
		this.currentStateQueryCoordinator = currentStateQueryCoordinator;
		this.matchRequestCommandService = matchRequestCommandService;
		this.matchProposalResponseService = matchProposalResponseService;
		this.matchPartyLeaveService = matchPartyLeaveService;
		this.currentUserAccessor = currentUserAccessor;
	}

	@DeleteMapping(path = "/parties/{partyId}/participants/me", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<CurrentMatchStateResponse>> leave(@PathVariable @Positive long partyId) {
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK,
			matchPartyLeaveService.leave(partyId, currentUserAccessor.requireCurrentUserId())));
	}

	@GetMapping(path = "/current", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<CurrentMatchStateResponse>> current() {
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK,
			currentStateQueryCoordinator.read(currentUserAccessor.requireCurrentUserId())));
	}

	@PostMapping(path = "/requests", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<CurrentMatchStateResponse>> create(
		@RequestHeader("Idempotency-Key") @Pattern(regexp = "[\\x21-\\x7E](?:[\\x20-\\x7E]{0,98}[\\x21-\\x7E])?")
		String idempotencyKey,
		@Valid @RequestBody
		MatchRequestCreateRequest request) {
		MatchRequestCommandService.CreateResult result = matchRequestCommandService.create(
			currentUserAccessor.requireCurrentUserId(), idempotencyKey, request);
		HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
		return ResponseEntity.status(status).body(ApiResponse.success(status, result.response()));
	}

	@DeleteMapping(path = "/requests/me", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<CurrentMatchStateResponse>> cancel() {
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK,
			matchRequestCommandService.cancel(currentUserAccessor.requireCurrentUserId())));
	}

	@PostMapping(path = "/proposals/{proposalId}/responses", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<CurrentMatchStateResponse>> respond(
		@PathVariable @Positive long proposalId,
		@RequestHeader("Idempotency-Key") @Pattern(regexp = "[\\x21-\\x7E](?:[\\x20-\\x7E]{0,98}[\\x21-\\x7E])?")
		String idempotencyKey,
		@Valid @RequestBody
		MatchProposalResponseRequest request) {
		long userId = currentUserAccessor.requireCurrentUserId();
		matchProposalResponseService.respond(userId, proposalId, request.action(), idempotencyKey);
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, currentStateQueryCoordinator.read(userId)));
	}
}
