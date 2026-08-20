package cloud.bamsongi.albammate.assistant.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.assistant.dto.AssistantDraftCreateRequest;
import cloud.bamsongi.albammate.assistant.dto.AssistantDraftPatchRequest;
import cloud.bamsongi.albammate.assistant.dto.AssistantDraftResponse;
import cloud.bamsongi.albammate.assistant.service.AssistantDraftService;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/assistant/drafts")
@RequiredArgsConstructor
public class AssistantDraftController {
	private final AssistantDraftService draftService;
	private final CurrentUserAccessor currentUserAccessor;

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<AssistantDraftResponse>> create(@RequestBody
	AssistantDraftCreateRequest request) {
		AssistantDraftResponse response = draftService.create(currentUserAccessor.requireCurrentUserId(), request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(HttpStatus.CREATED, response));
	}

	@GetMapping(path = "/{draftId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<AssistantDraftResponse>> get(@PathVariable @Positive long draftId) {
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK,
			draftService.get(currentUserAccessor.requireCurrentUserId(), draftId)));
	}

	@PatchMapping(path = "/{draftId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<AssistantDraftResponse>> update(
		@PathVariable @Positive long draftId, @RequestBody
		AssistantDraftPatchRequest request) {
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, draftService.update(
			currentUserAccessor.requireCurrentUserId(), draftId, request)));
	}

	@DeleteMapping(path = "/{draftId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> discard(@PathVariable @Positive long draftId) {
		draftService.discard(currentUserAccessor.requireCurrentUserId(), draftId);
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, java.util.Map.of()));
	}

	@PostMapping(path = "/{draftId}/confirm", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<AssistantDraftResponse.Result>> confirm(
		@PathVariable @Positive long draftId,
		@RequestHeader("Idempotency-Key")
		String idempotencyKey,
		@RequestBody
		ConfirmRequest request) {
		AssistantDraftService.ConfirmOutcome outcome = draftService.confirm(
			currentUserAccessor.requireCurrentUserId(), draftId, request.draftVersion(), idempotencyKey);
		HttpStatus status = outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
		return ResponseEntity.status(status).body(ApiResponse.success(status, outcome.result()));
	}

	public record ConfirmRequest(long draftVersion) {
	}
}
