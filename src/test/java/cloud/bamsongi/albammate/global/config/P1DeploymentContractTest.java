package cloud.bamsongi.albammate.global.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class P1DeploymentContractTest {

	private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath();

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
	void App2_주소가_없으면_Compose와_entrypoint가_즉시_거부한다() throws IOException {
		String compose = file("compose.production.yml");
		String entrypoint = file("frontend/docker-entrypoint.production.sh");

		assertTrue(compose.contains("${ALBAM_MATE_APP2_HOST:?ALBAM_MATE_APP2_HOST must be set}"));
		assertFalse(compose.contains("${ALBAM_MATE_APP2_HOST:-127.0.0.1}"));
		assertTrue(entrypoint.contains("ALBAM_MATE_APP2_HOST must be set"));
		assertTrue(entrypoint.contains("ALBAM_MATE_APP2_HOST must not include a port"));
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
			"# Redis (/etc/albam-mate/redis.env)");
		String redis = section(example, "# Redis (/etc/albam-mate/redis.env)", null);

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
		assertFalse(verifier.contains("ALBAM_MATE_RDS_CA_PATH"));
		assertFalse(verifier.contains("rds-ca-bundle.pem"));
	}

	@Test
	void 인프라_가이드는_로컬_계약과_미배정_AWS_배포_증거를_구분한다() throws IOException {
		String guide = file("docs/guides/AWS_MULTI_INSTANCE_INFRASTRUCTURE.md");

		assertTrue(guide.contains("원본 설정을 유지하고 /tmp 렌더링 설정으로 Nginx를 기동한다."));
		assertTrue(guide.contains("App1 `spring:8080`과 App2 private DNS `:8080`을 upstream으로 사용한다."));
		assertTrue(guide.contains("실제 ARM64 이미지 게시·노드별 환경 전달·배포·분산·복구·부하 증거"));
		assertTrue(guide.contains("로컬 검증 통과를 실제 AWS 4노드 배포 증거로 표현하지 않는다."));
	}

	private void assertSpringMemoryContract(String compose) {
		assertTrue(compose.contains("mem_limit: 512m"));
		assertTrue(compose.contains("JDK_JAVA_OPTIONS: -Xmx256m"));
	}

	private String file(String relativePath) throws IOException {
		return Files.readString(REPOSITORY_ROOT.resolve(relativePath), StandardCharsets.UTF_8);
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
