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

class DeploymentContractTest {

	private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath();
	private static final Pattern FORWARDED_FOR_REMOTE_ADDRESS_DIRECTIVE = Pattern.compile(
		"(?m)^[\\t ]*proxy_set_header X-Forwarded-For \\$remote_addr;[\\t ]*$");
	private static final Pattern FORWARDED_REMOVAL_DIRECTIVE = Pattern.compile(
		"(?m)^[\\t ]*proxy_set_header Forwarded \"\";[\\t ]*$");

	@Test
	void P2_일반_앱은_Flyway를_실행하지_않고_전용_migrator만_한번_실행한다() throws IOException {
		for (String composePath : new String[] {"compose.production.yml", "compose.app2.yml"}) {
			assertTrue(file(composePath).contains("SPRING_FLYWAY_ENABLED: false"));
		}
		String migrator = file("compose.migrator.yml");
		assertTrue(migrator.contains("SPRING_PROFILES_ACTIVE: production,migrator"));
		assertTrue(migrator.contains("SPRING_FLYWAY_ENABLED: true"));
		assertTrue(migrator.contains("restart: \"no\""));
		assertFalse(migrator.contains("notification-ops"));
	}

	@Test
	void T1_P2_CD는_성공한_develop_CI_head_SHA만_source로_사용한다() throws IOException {
		String trigger = file(".github/workflows/p2-cd-trigger.yml");
		String workflow = file(".github/workflows/p2-cd.yml");
		assertTrue(trigger.contains("github.event.workflow_run.head_sha"));
		assertTrue(trigger.contains("higher successful develop push CI run exists"));
		assertTrue(trigger.contains("workflow_id: 'ci.yml'"));
		assertTrue(trigger.contains("bamsongi-club/albam-mate/.github/workflows/p2-cd.yml@develop"));
		assertFalse(trigger.contains("concurrency:"));
		assertFalse(trigger.contains("github.sha"));
		assertFalse(trigger.contains("github.ref"));
		assertTrue(workflow.contains("group: p2-deploy"));
		assertTrue(workflow.contains("cancel-in-progress: false"));
	}

	@Test
	void T2_P2_CD는_SHA_pin과_OIDC_immutable_ARM64_계약을_사용한다() throws IOException {
		String workflow = file(".github/workflows/p2-cd.yml");
		assertTrue(workflow.contains("id-token: write"));
		assertTrue(workflow.contains("configure-aws-credentials@61815dcd50bd041e203e49132bacad1fd04d2708"));
		assertTrue(workflow.contains("P2_IMAGE_PUBLISH_ROLE_ARN"));
		assertTrue(workflow.contains("linux/arm64"));
		assertTrue(workflow.contains("org.opencontainers.image.revision"));
		assertTrue(workflow.contains("frontend/Dockerfile.production"));
		assertTrue(workflow.contains("/usr/local/bin/albam-mate-entrypoint"));
	}

	@Test
	void T3_P2_CD는_고정_digest_계약과_infra_IAM_경계를_유지한다() throws IOException {
		String workflow = file(".github/workflows/p2-cd.yml");
		assertTrue(workflow.contains("P2_DEPLOY_ROLE_ARN"));
		assertTrue(workflow.contains("aws ssm get-parameter"));
		assertTrue(workflow.contains("aws ssm send-command"));
		assertTrue(workflow.contains("--document-name \"$SSM_DOCUMENT\""));
		assertTrue(workflow.contains("runtimeSecretPrefix"));
		assertTrue(workflow.contains("RUNTIME_SECRET_PREFIX"));
		assertFalse(workflow.contains("AWS_ACCESS_KEY_ID"));
		assertFalse(workflow.contains("AWS_SECRET_ACCESS_KEY"));
	}

	@Test
	void T5_P2_CD는_host_local_인증_smoke를_호출하지_않는다() throws IOException {
		String workflow = file(".github/workflows/p2-cd.yml");
		assertTrue(workflow.contains("invoke verify-app1-candidate verify-app1"));
		assertFalse(workflow.toLowerCase().contains("smoke"));
		assertFalse(workflow.contains("deployment-verification.env"));
		assertFalse(workflow.toLowerCase().contains("csrf"));
	}

	@Test
	void T6_P2_CD는_LKG_app_rollback만_계약으로_남긴다() throws IOException {
		String workflow = file(".github/workflows/p2-cd.yml");
		assertTrue(workflow.contains("rollback_app2()"));
		assertTrue(workflow.contains("rollback_app1_app2()"));
		assertTrue(workflow.contains("databaseRollbackSupported == false"));
	}

	@Test
	void T7_P2_CD_receipt은_허용된_식별자만_남긴다() throws IOException {
		String workflow = file(".github/workflows/p2-cd.yml");
		assertTrue(workflow.contains(
			"receipt allowlist: SHA, CI URL, digest, role/target/command identifier, phase, LKG version/status"));
		assertFalse(workflow.contains("terraform apply"));
		assertTrue(workflow.contains("aws ssm send-command"));
		assertFalse(workflow.contains("AWS-RunShellScript"));
	}

	@Test
	void T8_P2_CD는_send_command_실패를_즉시_phase_실패로_끝낸다() throws IOException {
		String workflow = file(".github/workflows/p2-cd.yml");
		assertTrue(workflow.contains("if ! command_id=\"$(aws ssm send-command"));
		assertTrue(workflow.contains("|| [[ -z \"$command_id\" ]]; then"));
		// 실패 분기는 원인을 로그와 receipt에 남기고 non-zero로 끝난다.
		int failureBranch = workflow.indexOf("|| [[ -z \"$command_id\" ]]; then");
		int waitCall = workflow.indexOf("if wait_for_command", failureBranch);
		String branch = workflow.substring(failureBranch, waitCall);
		assertTrue(branch.contains("cat \"$error_file\" >&2"));
		assertTrue(branch.contains("status=failed"));
		assertTrue(branch.contains("return 1"));
		// 대상 상태를 오류 문자열로 판정해 배포를 성공으로 끝내지 않는다.
		assertFalse(workflow.contains("InvalidInstanceId"));
		assertFalse(workflow.contains("exit 0"));
	}

	@Test
	void 모든_Compose와_검증기와_부하_문서는_ALBAM_MATE_LOGIN_LIMIT만_쓴다() throws IOException {
		for (String composePath : new String[] {"compose.production.yml", "compose.app2.yml"}) {
			String compose = file(composePath);
			assertTrue(compose.contains("ALBAM_MATE_LOGIN_LIMIT: ${ALBAM_MATE_LOGIN_LIMIT:-30}"));
			assertFalse(compose.contains("APP_SECURITY_AUTHREQUEST_LOGINLIMIT"));
		}
		String localCompose = file("compose.local.yml");
		String localSpringOne = section(localCompose, "  spring-1:", "  spring-2:");
		String localSpringTwo = section(localCompose, "  spring-2:", "  proxy:");
		assertTrue(localSpringOne.contains("environment: &spring_environment"));
		assertTrue(localSpringOne.contains("ALBAM_MATE_LOGIN_LIMIT: ${ALBAM_MATE_LOGIN_LIMIT:-30}"));
		assertFalse(localSpringOne.contains("ALBAM_MATE_ROLE:"));
		assertTrue(localSpringTwo.contains("<<: *spring_environment"));
		assertTrue(localSpringTwo.contains("ALBAM_MATE_ROLE: app2"));
		assertFalse(localCompose.contains("APP_SECURITY_AUTHREQUEST_LOGINLIMIT"));
		assertEquals("${ALBAM_MATE_LOGIN_LIMIT:30}", yamlProperties("src/main/resources/application-local.yml")
			.getProperty("app.security.auth-request.login-limit"));
		assertTrue(file("scripts/verify-docker-deployment.mjs").contains("ALBAM_MATE_LOGIN_LIMIT: '20000'"));
		assertTrue(file("scripts/verify-docker-deployment.mjs").contains("ProductionLoginLimitDefaultTest"));
		assertFalse(file("scripts/verify-docker-deployment.mjs").contains("APP_SECURITY_AUTHREQUEST_LOGINLIMIT"));
		assertTrue(file("load-tests/k6/jiho/README.md").contains("ALBAM_MATE_LOGIN_LIMIT"));
		assertFalse(file("load-tests/k6/jiho/README.md").contains("APP_SECURITY_AUTHREQUEST_LOGINLIMIT"));
	}

	@Test
	void read_only_web은_tmp_렌더링_설정으로_healthz와_TLS를_기동한다() throws IOException {
		String entrypoint = file("frontend/docker-entrypoint.production.sh");

		assertTrue(entrypoint.contains("> /tmp/nginx.conf.rendered"));
		assertFalse(entrypoint.contains("cp /tmp/nginx.conf.rendered /etc/nginx/nginx.conf"));
		assertTrue(entrypoint.contains("-c /tmp/nginx.conf.rendered"));
	}

	@Test
	void nginx는_App1과_App2_upstream으로_라우팅하고_backend_bounded_헤더만_전달한다() throws IOException {
		String nginx = file("frontend/nginx.production.conf");

		assertTrue(nginx.contains("server spring:8080;"));
		assertTrue(nginx.contains("server ${ALBAM_MATE_APP2_HOST}:8080;"));
		assertFalse(nginx.contains("server 127.0.0.1:8080;"));
		assertFalse(nginx.contains("add_header X-Albam-Mate-Upstream"));
		assertEquals(3, count(nginx, "proxy_pass_header X-Albam-Mate-Upstream;"));
		String localNginx = file("frontend/nginx.local.conf");
		assertFalse(localNginx.contains("add_header X-Albam-Mate-Upstream "));
		assertTrue(localNginx.contains("add_header X-Albam-Mate-Upstream-Address $upstream_addr always;"));
		assertEquals(2, count(localNginx, "proxy_pass_header X-Albam-Mate-Upstream;"));
		String verifier = file("scripts/verify-docker-deployment.mjs");
		assertTrue(verifier.contains("assertUpstreamPair(body, upstream);"));
		assertTrue(verifier.contains("body === 'production-proxy-app1' && upstream === 'app1'"));
		assertTrue(verifier.contains("body === 'production-proxy-app2' && upstream === 'app2'"));
	}

	@Test
	void 모든_Spring_proxy는_XFF를_직접_관찰_주소로_덮어쓴다() throws IOException {
		assertSpringProxyOverwritesForwardedFor(file("frontend/nginx.production.conf"), 3);
		assertSpringProxyOverwritesForwardedFor(file("frontend/nginx.local.conf"), 2);
		assertSpringProxyOverwritesForwardedFor(file("frontend/nginx.conf"), 2);
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
	void App1과_App2_Spring은_1g과_256m_heap을_사용하고_이미지_JAVA_TOOL_OPTIONS를_보존한다()
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
	void T1_T3_App1과_App2는_동일한_AI_release_gate와_의존성_입력을_전달한다() throws IOException {
		String app1Compose = file("compose.production.yml");
		String app2Compose = file("compose.app2.yml");
		String example = file(".env.production.example");
		String app1Example = section(example, "# App1 (/etc/albam-mate/app1.env)", "# App2 (/etc/albam-mate/app2.env)");
		String app2Example = section(example, "# App2 (/etc/albam-mate/app2.env)",
			"# PostgreSQL (/etc/albam-mate/postgres.env)");

		for (String input : new String[] {
			"ALBAM_MATE_ASSISTANT_ENABLED", "ALBAM_MATE_ASSISTANT_PROVIDER",
			"ALBAM_MATE_ASSISTANT_PROVIDER_CONFIGURED", "ALBAM_MATE_ASSISTANT_NO_RETENTION_VERIFIED",
			"ALBAM_MATE_ASSISTANT_NO_TRAINING_VERIFIED", "ALBAM_MATE_ASSISTANT_RETENTION_MODE",
			"ALBAM_MATE_ASSISTANT_POLICY_VERSION",
			"ALBAM_MATE_ASSISTANT_POLICY_URL", "ALBAM_MATE_ASSISTANT_PRICING_SNAPSHOT",
			"ALBAM_MATE_ASSISTANT_INPUT_TOKEN_PRICE_USD_PER_MILLION",
			"ALBAM_MATE_ASSISTANT_OUTPUT_TOKEN_PRICE_USD_PER_MILLION",
			"ALBAM_MATE_ASSISTANT_MAX_INPUT_TOKENS", "ALBAM_MATE_ASSISTANT_MAX_OUTPUT_TOKENS",
			"ALBAM_MATE_ASSISTANT_OPENAI_API_KEY"
		}) {
			assertTrue(app1Compose.contains(input));
			assertTrue(app2Compose.contains(input));
			assertTrue(app1Example.contains(input + "="));
			assertTrue(app2Example.contains(input + "="));
		}
		assertFalse(example.contains("sk-"));
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
		assertTrue(compose.contains("mem_limit: 1g"));
		assertTrue(compose.contains("JDK_JAVA_OPTIONS: -Xmx256m"));
	}

	private void assertSpringProxyOverwritesForwardedFor(String nginx, int expectedSpringProxyLocationCount) {
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
		assertEquals(expectedSpringProxyLocationCount, springProxyLocationCount);
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

	private int count(String contents, String expected) {
		return contents.split(Pattern.quote(expected), -1).length - 1;
	}
}
