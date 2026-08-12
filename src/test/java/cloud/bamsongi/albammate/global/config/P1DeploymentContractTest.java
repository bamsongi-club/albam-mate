package cloud.bamsongi.albammate.global.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import cloud.bamsongi.albammate.global.security.ratelimit.InMemoryAuthenticationRequestLimiter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;

class P1DeploymentContractTest {

	private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath();
	private static final Pattern FORWARDED_FOR_REMOTE_ADDRESS_DIRECTIVE = Pattern.compile(
		"(?m)^[\\t ]*proxy_set_header X-Forwarded-For \\$remote_addr;[\\t ]*$");
	private static final Pattern FORWARDED_REMOVAL_DIRECTIVE = Pattern.compile(
		"(?m)^[\\t ]*proxy_set_header Forwarded \"\";[\\t ]*$");

	@Test
	void 운영_Compose와_검증기와_부하_문서는_ALBAM_MATE_LOGIN_LIMIT만_쓴다() throws IOException {
		for (String composePath : new String[] {"compose.production.yml", "compose.app2.yml"}) {
			String compose = file(composePath);
			assertTrue(compose.contains("ALBAM_MATE_LOGIN_LIMIT: ${ALBAM_MATE_LOGIN_LIMIT:-30}"));
			assertFalse(compose.contains("APP_SECURITY_AUTHREQUEST_LOGINLIMIT"));
		}
		assertTrue(file("scripts/verify-docker-deployment.mjs").contains("ALBAM_MATE_LOGIN_LIMIT: '20000'"));
		assertFalse(file("scripts/verify-docker-deployment.mjs").contains("APP_SECURITY_AUTHREQUEST_LOGINLIMIT"));
		assertTrue(file("load-tests/k6/auth-notification/README.md").contains("ALBAM_MATE_LOGIN_LIMIT"));
		assertFalse(file("load-tests/k6/auth-notification/README.md").contains("APP_SECURITY_AUTHREQUEST_LOGINLIMIT"));
	}

	@Test
	void read_only_web은_tmp_렌더링_설정으로_healthz와_TLS를_기동한다() throws IOException {
		String entrypoint = file("frontend/docker-entrypoint.production.sh");

		assertTrue(entrypoint.contains("> /tmp/nginx.conf.rendered"));
		assertFalse(entrypoint.contains("cp /tmp/nginx.conf.rendered /etc/nginx/nginx.conf"));
		assertTrue(entrypoint.contains("-c /tmp/nginx.conf.rendered"));
	}

	@Test
	void nginx는_App1과_App2_upstream_응답과_헤더를_모두_노출한다() throws IOException {
		String nginx = file("frontend/nginx.production.conf");

		assertTrue(nginx.contains("server spring:8080;"));
		assertTrue(nginx.contains("server ${ALBAM_MATE_APP2_HOST}:8080;"));
		assertFalse(nginx.contains("server 127.0.0.1:8080;"));
		assertTrue(nginx.contains("add_header X-Albam-Mate-Upstream $upstream_addr always;"));
	}

	@Test
	void 모든_Spring_proxy는_XFF를_직접_관찰_주소로_덮어쓴다() throws IOException {
		assertSpringProxyOverwritesForwardedFor(file("frontend/nginx.production.conf"));
		assertSpringProxyOverwritesForwardedFor(file("frontend/nginx.local.conf"));
		assertSpringProxyOverwritesForwardedFor(file("frontend/nginx.conf"));
	}

	@Test
	void production과_local은_framework_전달_헤더를_사용하고_기본_설정은_비활성화한다() throws IOException {
		assertEquals("framework", yamlProperties("src/main/resources/application-production.yml")
			.getProperty("server.forward-headers-strategy"));
		assertEquals("framework", yamlProperties("src/main/resources/application-local.yml")
			.getProperty("server.forward-headers-strategy"));
		assertNull(yamlProperties("src/main/resources/application.yml")
			.getProperty("server.forward-headers-strategy"));
	}

	@Test
	void 제거된_외부_Forwarded는_Spring_remote_addr와_제한_버킷을_바꾸지_못한다()
		throws IOException, ServletException {
		String nginxObservedAddress = "203.0.113.10";
		String maliciousForwardedAddress = "198.51.100.10";
		InMemoryAuthenticationRequestLimiter limiter = inMemoryLimiter();
		String firstRemoteAddress = springRemoteAddress(nginxObservedAddress);
		String secondRemoteAddress = springRemoteAddress(nginxObservedAddress);

		assertEquals(
			maliciousForwardedAddress,
			springRemoteAddressWithForwarded("for=" + maliciousForwardedAddress, nginxObservedAddress));
		assertEquals(nginxObservedAddress, firstRemoteAddress);
		assertEquals(nginxObservedAddress, secondRemoteAddress);
		assertTrue(limiter.checkAndRecordSignup(firstRemoteAddress).allowed());
		assertTrue(limiter.checkAndRecordSignup(secondRemoteAddress).allowed());
		assertEquals(1, limiter.ipBucketCount());
	}

	@Test
	void 같은_Nginx_제공_주소는_Spring_remote_addr와_인스턴스별_제한_버킷으로_수렴한다()
		throws IOException, ServletException {
		String nginxObservedAddress = "203.0.113.10";
		InMemoryAuthenticationRequestLimiter limiter = inMemoryLimiter();
		String signupRemoteAddress = springRemoteAddress(nginxObservedAddress);
		String loginRemoteAddress = springRemoteAddress(nginxObservedAddress);

		assertEquals(nginxObservedAddress, signupRemoteAddress);
		assertEquals(nginxObservedAddress, loginRemoteAddress);
		assertTrue(limiter.checkAndRecordSignup(signupRemoteAddress).allowed());
		assertTrue(limiter.checkAndRecordSignup(signupRemoteAddress).allowed());
		assertTrue(limiter.checkAndRecordLogin(loginRemoteAddress).allowed());
		assertTrue(limiter.checkAndRecordLogin(loginRemoteAddress).allowed());
		assertEquals(1, limiter.ipBucketCount());
	}

	@Test
	void 다른_Nginx_제공_주소는_서로_다른_Spring_remote_addr와_인스턴스별_제한_버킷으로_유지된다()
		throws IOException, ServletException {
		String firstAddress = springRemoteAddress("203.0.113.10");
		String secondAddress = springRemoteAddress("203.0.113.11");
		InMemoryAuthenticationRequestLimiter limiter = inMemoryLimiter();

		assertEquals("203.0.113.10", firstAddress);
		assertEquals("203.0.113.11", secondAddress);
		assertFalse(firstAddress.equals(secondAddress));
		assertTrue(limiter.checkAndRecordSignup(firstAddress).allowed());
		assertTrue(limiter.checkAndRecordSignup(secondAddress).allowed());
		assertTrue(limiter.checkAndRecordLogin(firstAddress).allowed());
		assertTrue(limiter.checkAndRecordLogin(secondAddress).allowed());
		assertEquals(2, limiter.ipBucketCount());
	}

	@Test
	void App2_주소가_없으면_Compose와_entrypoint가_즉시_거부한다() throws IOException {
		String compose = file("compose.production.yml");
		String entrypoint = file("frontend/docker-entrypoint.production.sh");

		assertTrue(compose.contains("${ALBAM_MATE_APP2_HOST:?ALBAM_MATE_APP2_HOST must be set}"));
		assertFalse(compose.contains("${ALBAM_MATE_APP2_HOST:-127.0.0.1}"));
		assertTrue(entrypoint.contains("ALBAM_MATE_APP2_HOST must be set"));
		assertTrue(entrypoint.contains("ALBAM_MATE_APP2_HOST must not include a port"));
		assertTrue(entrypoint.contains("private DNS hostname"));
		assertTrue(entrypoint.contains("grep -Eq"));
		assertFalse(entrypoint.contains("ALBAM_MATE_APP2_HOST:-127.0.0.1"));
	}

	@Test
	void App1과_App2_Spring은_512m과_256m_heap을_사용하고_이미지_JAVA_TOOL_OPTIONS를_보존한다()
		throws IOException {
		assertSpringMemoryContract(file("compose.production.yml"));
		assertSpringMemoryContract(file("compose.app2.yml"));
		assertTrue(file("Dockerfile").contains("JAVA_TOOL_OPTIONS"));
	}

	@Test
	void 운영_환경변수_예시는_노드별_최소_권한_입력을_구분한다() throws IOException {
		String example = file(".env.production.example");
		String app1 = section(example, "# App1 (/etc/albam-mate/app1.env)", "# App2 (/etc/albam-mate/app2.env)");
		String app2 = section(example, "# App2 (/etc/albam-mate/app2.env)",
			"# PostgreSQL (/etc/albam-mate/postgres.env)");
		String postgres = section(example, "# PostgreSQL (/etc/albam-mate/postgres.env)",
			"# Redis (전용 환경 파일 없음)");
		String redis = section(example, "# Redis (전용 환경 파일 없음)", null);

		assertTrue(app1.contains("ALBAM_MATE_APP2_HOST=app-b.albam-mate.internal"));
		assertTrue(app1.contains("ALBAM_MATE_HTTPS_BIND_ADDRESS=127.0.0.1"));
		assertTrue(app1.contains("JDK_JAVA_OPTIONS=-Xmx256m"));
		assertOptionalApplicationInputs(app1);
		assertFalse(app2.contains("ALBAM_MATE_APP2_HOST="));
		assertFalse(app2.contains("ALBAM_MATE_TLS_PATH="));
		assertTrue(app2.contains("JDK_JAVA_OPTIONS=-Xmx256m"));
		assertOptionalApplicationInputs(app2);
		assertTrue(postgres.contains("ALBAM_MATE_DB_PASSWORD="));
		assertFalse(postgres.contains("ALBAM_MATE_REDIS_HOST="));
		assertFalse(redis.contains("ALBAM_MATE_DB_PASSWORD="));
		assertFalse(example.contains("ALBAM_MATE_RDS_CA_PATH"));
		assertFalse(example.contains("amazonaws.com"));
	}

	@Test
	void PostgreSQL_healthcheck은_컨테이너_내부_사용자와_DB를_검사한다() throws IOException {
		String compose = file("compose.db.yml");

		assertTrue(compose.contains("$$POSTGRES_USER"));
		assertTrue(compose.contains("$$POSTGRES_DB"));
		String verifier = file("scripts/verify-docker-deployment.mjs");
		String databaseHealthcheck = verifier.substring(verifier.indexOf("function verifyDatabaseHealthcheck"));
		assertTrue(databaseHealthcheck.contains("'config', '--format', 'json'"));
		assertTrue(databaseHealthcheck.contains("Healthcheck.Test"));
	}

	@Test
	void 운영_검증기는_RDS_CA_없이_App2와_web과_역할별_Compose_계약을_검증한다() throws IOException {
		String verifier = file("scripts/verify-docker-deployment.mjs");

		assertTrue(verifier.contains("compose.app2.yml"));
		assertTrue(verifier.contains("compose.db.yml"));
		assertTrue(verifier.contains("ALBAM_MATE_APP2_HOST"));
		assertTrue(verifier.contains("JDK_JAVA_OPTIONS"));
		assertTrue(verifier.contains("for (let attempt = 0; attempt < 12; attempt += 1)"));
		assertTrue(verifier.contains("observedBodies.has('production-proxy-app1')"));
		assertTrue(verifier.contains("observedBodies.has('production-proxy-app2')"));
		assertFalse(verifier.contains("ALBAM_MATE_RDS_CA_PATH"));
		assertFalse(verifier.contains("rds-ca-bundle.pem"));
	}

	@Test
	void 인프라_가이드는_로컬_계약과_미배정_AWS_배포_증거를_구분한다() throws IOException {
		String guide = file("docs/guides/AWS_MULTI_INSTANCE_INFRASTRUCTURE.md");

		assertTrue(guide.contains("원본 설정을 유지하고 /tmp 렌더링 설정으로 Nginx를 기동한다."));
		assertTrue(guide.contains("App1 `spring:8080`과 App2 private DNS `:8080`을 upstream으로 사용한다."));
		assertTrue(guide.contains("/etc/albam-mate/app1.env"));
		assertTrue(guide.contains("/etc/albam-mate/app2.env"));
		assertTrue(guide.contains("compose.app2.yml"));
		assertTrue(guide.contains("/etc/albam-mate/postgres.env"));
		assertTrue(guide.contains("실제 ARM64 이미지 게시·노드별 환경 전달·배포·분산·복구·부하 증거"));
		assertTrue(guide.contains("로컬 검증 통과를 실제 AWS 4노드 배포 증거로 표현하지 않는다."));
	}

	private void assertSpringMemoryContract(String compose) {
		assertTrue(compose.contains("mem_limit: 512m"));
		assertTrue(compose.contains("JDK_JAVA_OPTIONS: -Xmx256m"));
	}

	private void assertSpringProxyOverwritesForwardedFor(String nginx) {
		assertFalse(nginx.contains("$proxy_add_x_forwarded_for"));
		int springProxyLocationCount = 0;
		Matcher locationMatcher = Pattern
			.compile("(?s)location\\s+[^\\{]+\\{.*?\\n\\s*}")
			.matcher(nginx);
		while (locationMatcher.find()) {
			String location = locationMatcher.group();
			if (!location.contains("proxy_pass")) {
				continue;
			}

			springProxyLocationCount++;
			assertFalse(location.contains("$proxy_add_x_forwarded_for"));
			assertEquals(1, countForwardedForRemoteAddressDirectives(location));
			assertEquals(1, countForwardedRemovalDirectives(location));
		}
		assertEquals(2, springProxyLocationCount);
	}

	private int countForwardedForRemoteAddressDirectives(String contents) {
		var directiveMatcher = FORWARDED_FOR_REMOTE_ADDRESS_DIRECTIVE.matcher(contents);
		int count = 0;
		while (directiveMatcher.find()) {
			count++;
		}
		return count;
	}

	private int countForwardedRemovalDirectives(String contents) {
		var directiveMatcher = FORWARDED_REMOVAL_DIRECTIVE.matcher(contents);
		int count = 0;
		while (directiveMatcher.find()) {
			count++;
		}
		return count;
	}

	private String springRemoteAddress(String nginxObservedAddress) throws IOException, ServletException {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("172.20.0.10");
		request.addHeader("X-Forwarded-For", nginxObservedAddress);
		AtomicReference<String> remoteAddress = new AtomicReference<>();

		new ForwardedHeaderFilter().doFilter(
			request,
			new MockHttpServletResponse(),
			(servletRequest, servletResponse) -> remoteAddress.set(
				((HttpServletRequest)servletRequest).getRemoteAddr()));

		return remoteAddress.get();
	}

	private String springRemoteAddressWithForwarded(String forwarded, String nginxObservedAddress)
		throws IOException, ServletException {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("172.20.0.10");
		request.addHeader("Forwarded", forwarded);
		request.addHeader("X-Forwarded-For", nginxObservedAddress);
		AtomicReference<String> remoteAddress = new AtomicReference<>();

		new ForwardedHeaderFilter().doFilter(
			request,
			new MockHttpServletResponse(),
			(servletRequest, servletResponse) -> remoteAddress.set(
				((HttpServletRequest)servletRequest).getRemoteAddr()));

		return remoteAddress.get();
	}

	private InMemoryAuthenticationRequestLimiter inMemoryLimiter() {
		AuthenticationRequestProtectionProperties properties = new AuthenticationRequestProtectionProperties();
		properties.setWindow(Duration.ofSeconds(10));
		properties.setMaxIpKeys(5);
		properties.setMaxFailureKeys(2);
		return new InMemoryAuthenticationRequestLimiter(
			properties,
			Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
	}

	private String file(String relativePath) throws IOException {
		return Files.readString(REPOSITORY_ROOT.resolve(relativePath), StandardCharsets.UTF_8);
	}

	private Properties yamlProperties(String relativePath) {
		YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
		factory.setResources(new FileSystemResource(REPOSITORY_ROOT.resolve(relativePath)));
		return factory.getObject();
	}

	private void assertOptionalApplicationInputs(String section) {
		assertTrue(section.contains("ALBAM_MATE_GOOGLE_OAUTH_CLIENT_ID="));
		assertTrue(section.contains("ALBAM_MATE_NAVER_OAUTH_CLIENT_SECRET="));
		assertTrue(section.contains("ALBAM_MATE_KAKAO_OAUTH_CLIENT_SECRET="));
		assertTrue(section.contains("ALBAM_MATE_CHAT_WEBSOCKET_ALLOWED_ORIGIN="));
		assertTrue(section.contains("ALBAM_MATE_LOG_MAX_SIZE="));
		assertTrue(section.contains("ALBAM_MATE_LOG_MAX_FILES="));
	}

	private String section(String contents, String start, String end) {
		int startIndex = contents.indexOf(start);
		assertTrue(startIndex >= 0, () -> "missing section: " + start);
		int endIndex = end == null ? contents.length() : contents.indexOf(end, startIndex + start.length());
		assertTrue(endIndex >= 0, () -> "missing section: " + end);
		return contents.substring(startIndex, endIndex);
	}
}
