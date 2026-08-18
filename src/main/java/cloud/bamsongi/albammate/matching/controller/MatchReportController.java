package cloud.bamsongi.albammate.matching.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import cloud.bamsongi.albammate.matching.dto.MatchReportCreateRequest;
import cloud.bamsongi.albammate.matching.dto.MatchReportReceiptResponse;
import cloud.bamsongi.albammate.matching.service.command.MatchReportCommandService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/matches/parties/{partyId}/reports")
@RequiredArgsConstructor
public class MatchReportController {

	private final MatchReportCommandService commandService;
	private final CurrentUserAccessor currentUserAccessor;

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<MatchReportReceiptResponse>> report(
		@PathVariable @Positive long partyId,
		@Valid @RequestBody
		MatchReportCreateRequest request) {
		MatchReportReceiptResponse receipt = commandService.report(
			currentUserAccessor.requireCurrentUserId(), partyId, request.participantRef(), request.reason());
		HttpStatus status = receipt.alreadyReceived() ? HttpStatus.OK : HttpStatus.CREATED;
		return ResponseEntity.status(status).body(ApiResponse.success(status, receipt));
	}
}
