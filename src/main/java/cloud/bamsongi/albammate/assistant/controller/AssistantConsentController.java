package cloud.bamsongi.albammate.assistant.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.assistant.dto.AssistantConsentRequest;
import cloud.bamsongi.albammate.assistant.dto.AssistantConsentResponse;
import cloud.bamsongi.albammate.assistant.service.AssistantConsentService;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import lombok.RequiredArgsConstructor;

/** 로그인 사용자의 외부 AI 처리 동의·철회 HTTP 경계다. */
@RestController
@RequestMapping("/api/assistant/consent")
@RequiredArgsConstructor
public class AssistantConsentController {

	private final AssistantConsentService consentService;
	private final CurrentUserAccessor currentUserAccessor;

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<AssistantConsentResponse>> getConsent() {
		return ResponseEntity.ok(ApiResponse.success(
			HttpStatus.OK,
			consentService.getConsent(currentUserAccessor.requireCurrentUserId())));
	}

	@PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<AssistantConsentResponse>> changeConsent(
		@RequestBody
		AssistantConsentRequest request) {
		return ResponseEntity.ok(ApiResponse.success(
			HttpStatus.OK,
			consentService.changeConsent(currentUserAccessor.requireCurrentUserId(), request)));
	}
}
