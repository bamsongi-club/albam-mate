package cloud.bamsongi.albammate.matching.controller;

import static cloud.bamsongi.albammate.global.security.endpoint.ApiEndpointAuthenticationMode.AUTHENTICATED;

import java.util.List;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.global.security.endpoint.ApiEndpointPolicy;
import cloud.bamsongi.albammate.global.security.endpoint.ApiEndpointPolicyContributor;

/** MATCH 차단 API의 인증·CSRF 경계를 등록한다. */
@Component
public class MatchBlockEndpointPolicyContributor implements ApiEndpointPolicyContributor {

	@Override
	public List<ApiEndpointPolicy> policies() {
		return List.of(
			new ApiEndpointPolicy(HttpMethod.GET, "/api/matches/blocks", AUTHENTICATED, false),
			new ApiEndpointPolicy(
				HttpMethod.PUT, "/api/matches/parties/{partyId}/participants/{participantRef}/block", AUTHENTICATED,
				true),
			new ApiEndpointPolicy(HttpMethod.DELETE, "/api/matches/blocks/{blockId}", AUTHENTICATED, true));
	}
}
