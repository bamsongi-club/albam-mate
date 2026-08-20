package cloud.bamsongi.albammate.assistant.controller;

import static cloud.bamsongi.albammate.global.security.endpoint.ApiEndpointAuthenticationMode.AUTHENTICATED;

import java.util.List;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.global.security.endpoint.ApiEndpointPolicy;
import cloud.bamsongi.albammate.global.security.endpoint.ApiEndpointPolicyContributor;

/** AI-01 동의 API의 인증·CSRF 경계를 등록한다. */
@Component
public class AssistantConsentEndpointPolicyContributor implements ApiEndpointPolicyContributor {

	@Override
	public List<ApiEndpointPolicy> policies() {
		return List.of(
			new ApiEndpointPolicy(HttpMethod.GET, "/api/assistant/consent", AUTHENTICATED, false),
			new ApiEndpointPolicy(HttpMethod.PUT, "/api/assistant/consent", AUTHENTICATED, true),
			new ApiEndpointPolicy(HttpMethod.POST, "/api/assistant/recommendations", AUTHENTICATED, true),
			new ApiEndpointPolicy(HttpMethod.POST, "/api/assistant/drafts", AUTHENTICATED, true),
			new ApiEndpointPolicy(HttpMethod.GET, "/api/assistant/drafts/active", AUTHENTICATED, false),
			new ApiEndpointPolicy(HttpMethod.PATCH, "/api/assistant/drafts/{draftId}", AUTHENTICATED, true),
			new ApiEndpointPolicy(HttpMethod.DELETE, "/api/assistant/drafts/{draftId}", AUTHENTICATED, true),
			new ApiEndpointPolicy(HttpMethod.POST, "/api/assistant/drafts/{draftId}/confirm", AUTHENTICATED, true));
	}
}
