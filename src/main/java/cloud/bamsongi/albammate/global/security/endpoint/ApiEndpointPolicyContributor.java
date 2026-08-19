package cloud.bamsongi.albammate.global.security.endpoint;

import java.util.List;

/** 업무 모듈이 공개 Controller 없이도 테스트 가능한 endpoint 정책을 기여하는 계약이다. */
@FunctionalInterface
public interface ApiEndpointPolicyContributor {

	List<ApiEndpointPolicy> policies();
}
