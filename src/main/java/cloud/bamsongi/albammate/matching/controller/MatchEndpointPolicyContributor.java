package cloud.bamsongi.albammate.matching.controller;

import static cloud.bamsongi.albammate.global.security.endpoint.ApiEndpointAuthenticationMode.AUTHENTICATED;

import java.util.List;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.global.security.endpoint.ApiEndpointPolicy;
import cloud.bamsongi.albammate.global.security.endpoint.ApiEndpointPolicyContributor;

@Component
public class MatchEndpointPolicyContributor implements ApiEndpointPolicyContributor {

	@Override
	public List<ApiEndpointPolicy> policies() {
		return List.of(
			new ApiEndpointPolicy(HttpMethod.GET, "/api/matches/current", AUTHENTICATED, false),
			new ApiEndpointPolicy(HttpMethod.POST, "/api/matches/requests", AUTHENTICATED, true),
			new ApiEndpointPolicy(HttpMethod.DELETE, "/api/matches/requests/me", AUTHENTICATED, true),
			new ApiEndpointPolicy(HttpMethod.POST, "/api/matches/proposals/{proposalId}/responses", AUTHENTICATED,
				true),
			new ApiEndpointPolicy(HttpMethod.DELETE, "/api/matches/parties/{partyId}/participants/me", AUTHENTICATED,
				true));
	}
}
