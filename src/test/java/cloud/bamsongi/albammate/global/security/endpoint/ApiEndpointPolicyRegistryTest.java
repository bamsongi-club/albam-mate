package cloud.bamsongi.albammate.global.security.endpoint;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest
class ApiEndpointPolicyRegistryTest {

	@Autowired
	private ApiEndpointPolicyRegistry endpointPolicyRegistry;

	@Autowired
	private RequestMappingHandlerMapping requestMappingHandlerMapping;

	@Test
	void 애플리케이션_API_HandlerMethod와_정책_등록부가_정확히_일치한다() {
		assertDoesNotThrow(
			() -> endpointPolicyRegistry.assertMatchesMvcEndpoints(applicationApiEndpoints()));
	}

	@Test
	void GET가_제공하는_HEAD도_원래_GET의_인증_정책을_적용한다() {
		for (String path : List.of(
			"/api/users/me",
			"/api/users/me/rooms",
			"/api/users/me/notifications",
			"/api/users/me/notifications/unread-count")) {
			MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.HEAD.name(), path);
			request.setServletPath(path);

			assertTrue(endpointPolicyRegistry.authenticatedRequestMatcher().matches(request));
			assertFalse(endpointPolicyRegistry.publicRequestMatcher().matches(request));
		}
	}

	@Test
	void 참가_취소_DELETE는_인증과_CSRF_정책에_등록된다() {
		String path = "/api/rooms/1/participants/me";
		MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.DELETE.name(), path);
		request.setServletPath(path);

		assertTrue(endpointPolicyRegistry.authenticatedRequestMatcher().matches(request));
		assertTrue(endpointPolicyRegistry.csrfProtectionRequestMatcher().matches(request));
	}

	@Test
	void 채팅_메시지_POST는_인증과_CSRF_정책에_등록된다() {
		String path = "/api/rooms/1/chat/messages";
		MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.POST.name(), path);
		request.setServletPath(path);

		assertTrue(endpointPolicyRegistry.authenticatedRequestMatcher().matches(request));
		assertTrue(endpointPolicyRegistry.csrfProtectionRequestMatcher().matches(request));
	}

	@Test
	void 알림_읽음_두_PATCH는_인증과_CSRF_정책에_등록된다() {
		for (String path : List.of("/api/users/me/notifications", "/api/users/me/notifications/1")) {
			MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.PATCH.name(), path);
			request.setServletPath(path);

			assertTrue(endpointPolicyRegistry.authenticatedRequestMatcher().matches(request));
			assertTrue(endpointPolicyRegistry.csrfProtectionRequestMatcher().matches(request));
		}
	}

	@Test
	void 방_상세_GET은_선택_인증이며_CSRF_보호_대상이_아니다() {
		String path = "/api/rooms/1";
		MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.GET.name(), path);
		request.setServletPath(path);

		assertTrue(
			endpointPolicyRegistry
				.policies()
				.contains(
					new ApiEndpointPolicy(
						HttpMethod.GET,
						"/api/rooms/{roomId}",
						ApiEndpointAuthenticationMode.OPTIONAL_AUTHENTICATION,
						false)));
		assertTrue(endpointPolicyRegistry.knownEndpointPathMatcher().matches(request));
		assertFalse(endpointPolicyRegistry.csrfProtectionRequestMatcher().matches(request));
	}

	@Test
	void 보호_리소스_경로의_미지원_상태변경_메서드는_MVC_405을_위해_통과시킨다() {
		MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.DELETE.name(), "/api/rooms/1");
		request.setServletPath("/api/rooms/1");

		assertTrue(endpointPolicyRegistry.knownEndpointPathMatcher().matches(request));
		assertTrue(endpointPolicyRegistry.protectedFutureSubpathMatcher().matches(request));
	}

	@Test
	void 랭킹_미등록_하위_경로는_인증_대상으로_처리된다() {
		String path = "/api/game-rankings/1";
		MockHttpServletRequest request = new MockHttpServletRequest(HttpMethod.GET.name(), path);
		request.setServletPath(path);

		assertFalse(endpointPolicyRegistry.knownEndpointPathMatcher().matches(request));
		assertTrue(endpointPolicyRegistry.protectedFutureSubpathMatcher().matches(request));
	}

	@Test
	void MATCH_정책_contributor를_실제_Spring_Registry_bean에_합성해도_미등록_하위_경로는_보호한다() {
		String unregisteredPath = "/api/matches/future-endpoint";
		MockHttpServletRequest unregisteredRequest = new MockHttpServletRequest(HttpMethod.GET.name(),
			unregisteredPath);
		unregisteredRequest.setServletPath(unregisteredPath);
		assertTrue(endpointPolicyRegistry.protectedFutureSubpathMatcher().matches(unregisteredRequest));

		ApiEndpointPolicy contributorPolicy = new ApiEndpointPolicy(
			HttpMethod.GET,
			"/api/matches/current",
			ApiEndpointAuthenticationMode.AUTHENTICATED,
			false);
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.registerBean(ApiEndpointPolicyContributor.class, () -> () -> List.of(contributorPolicy));
			RootBeanDefinition registryDefinition = new RootBeanDefinition(ApiEndpointPolicyRegistry.class);
			registryDefinition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
			context.registerBeanDefinition("apiEndpointPolicyRegistry", registryDefinition);
			context.refresh();

			ApiEndpointPolicyRegistry registry = context.getBean(ApiEndpointPolicyRegistry.class);
			MockHttpServletRequest registeredRequest = new MockHttpServletRequest(HttpMethod.GET.name(),
				"/api/matches/current");
			registeredRequest.setServletPath("/api/matches/current");
			assertTrue(registry.authenticatedRequestMatcher().matches(registeredRequest));
			assertTrue(registry.knownEndpointPathMatcher().matches(registeredRequest));
		}
	}

	@Test
	void 미등록_MVC_API를_검출한다() {
		ApiEndpointPolicyRegistry registry = ApiEndpointPolicyRegistry.forPolicies(
			List.of(
				new ApiEndpointPolicy(
					HttpMethod.GET,
					"/api/current",
					ApiEndpointAuthenticationMode.PUBLIC,
					false)));

		IllegalStateException exception = assertThrows(
			IllegalStateException.class,
			() -> registry.assertMatchesMvcEndpoints(
				List.of(
					new ApiEndpointMapping(
						HttpMethod.POST, "/api/new-endpoint"))));

		assertTrue(exception.getMessage().contains("unregistered"));
	}

	@Test
	void HTTP_메서드가_없는_API_매핑은_정책_대조를_우회할_수_없다() {
		RequestMappingInfo methodlessApiMapping = RequestMappingInfo.paths("/api/methodless").build();

		IllegalStateException exception = assertThrows(IllegalStateException.class,
			() -> mappingsFor(methodlessApiMapping));

		assertTrue(exception.getMessage().contains("HTTP method"));
	}

	@Test
	void 고아_정책과_중복_또는_CSFR_누락_정책을_검출한다() {
		ApiEndpointPolicy policy = new ApiEndpointPolicy(
			HttpMethod.GET,
			"/api/current",
			ApiEndpointAuthenticationMode.PUBLIC,
			false);

		IllegalStateException orphaned = assertThrows(
			IllegalStateException.class,
			() -> ApiEndpointPolicyRegistry.forPolicies(List.of(policy))
				.assertMatchesMvcEndpoints(List.of()));
		assertTrue(orphaned.getMessage().contains("orphaned"));
		assertThrows(
			IllegalStateException.class,
			() -> ApiEndpointPolicyRegistry.forPolicies(List.of(policy, policy)));
		assertThrows(
			IllegalStateException.class,
			() -> ApiEndpointPolicyRegistry.forPolicies(
				List.of(
					new ApiEndpointPolicy(
						HttpMethod.POST,
						"/api/protected-write",
						ApiEndpointAuthenticationMode.AUTHENTICATED,
						false))));
	}

	private Collection<ApiEndpointMapping> applicationApiEndpoints() {
		return requestMappingHandlerMapping.getHandlerMethods().entrySet().stream()
			.filter(entry -> isApplicationController(entry.getValue()))
			.flatMap(entry -> mappingsFor(entry.getKey()).stream())
			.filter(mapping -> mapping.pathPattern().startsWith("/api/"))
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	private boolean isApplicationController(HandlerMethod handlerMethod) {
		return handlerMethod.getBeanType().getPackageName().startsWith("cloud.bamsongi.albammate");
	}

	private Set<ApiEndpointMapping> mappingsFor(RequestMappingInfo mappingInfo) {
		Set<String> apiPaths = mappingInfo.getPatternValues().stream()
			.filter(pathPattern -> pathPattern.startsWith("/api/"))
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
		if (apiPaths.isEmpty()) {
			return Set.of();
		}
		if (mappingInfo.getMethodsCondition().getMethods().isEmpty()) {
			throw new IllegalStateException(
				"API HandlerMethod must declare at least one HTTP method: " + apiPaths);
		}
		return mappingInfo.getMethodsCondition().getMethods().stream()
			.flatMap(
				requestMethod -> apiPaths.stream()
					.map(
						pattern -> new ApiEndpointMapping(
							HttpMethod.valueOf(
								requestMethod.name()),
							pattern)))
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}
}
