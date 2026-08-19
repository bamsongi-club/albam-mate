package cloud.bamsongi.albammate.matching.controller;

import java.util.List;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.global.security.endpoint.ApiEndpointAuthenticationMode;
import cloud.bamsongi.albammate.global.security.endpoint.ApiEndpointPolicy;
import cloud.bamsongi.albammate.global.security.endpoint.ApiEndpointPolicyContributor;

@Component
public class MatchReportEndpointPolicyContributor implements ApiEndpointPolicyContributor {

	@Override
	public List<ApiEndpointPolicy> policies() {
		return List.of(new ApiEndpointPolicy(
			HttpMethod.POST,
			"/api/matches/parties/{partyId}/reports",
			ApiEndpointAuthenticationMode.AUTHENTICATED,
			true));
	}
}
