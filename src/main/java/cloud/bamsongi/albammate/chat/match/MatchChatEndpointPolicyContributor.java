package cloud.bamsongi.albammate.chat.match;

import java.util.List;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.global.security.endpoint.ApiEndpointAuthenticationMode;
import cloud.bamsongi.albammate.global.security.endpoint.ApiEndpointPolicy;
import cloud.bamsongi.albammate.global.security.endpoint.ApiEndpointPolicyContributor;

/** MATCH 채팅 HTTP 엔드포인트의 인증·CSRF 정책을 {@code ApiEndpointPolicyRegistry}에 등록한다. */
@Component
public class MatchChatEndpointPolicyContributor implements ApiEndpointPolicyContributor {

	private static final String MESSAGES_PATH = "/api/matches/parties/{partyId}/chat/messages";
	private static final String WS_PATH = "/api/matches/parties/{partyId}/chat/ws";

	@Override
	public List<ApiEndpointPolicy> policies() {
		return List.of(
			new ApiEndpointPolicy(HttpMethod.POST, MESSAGES_PATH, ApiEndpointAuthenticationMode.AUTHENTICATED, true),
			new ApiEndpointPolicy(HttpMethod.GET, MESSAGES_PATH, ApiEndpointAuthenticationMode.AUTHENTICATED, false),
			new ApiEndpointPolicy(HttpMethod.GET, WS_PATH, ApiEndpointAuthenticationMode.AUTHENTICATED, false));
	}
}
