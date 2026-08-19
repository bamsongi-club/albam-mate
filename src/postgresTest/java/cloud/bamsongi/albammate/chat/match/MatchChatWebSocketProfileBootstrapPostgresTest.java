package cloud.bamsongi.albammate.chat.match;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;

/**
 * local·production 프로필에서 {@link MatchChatWebSocketHandler}가 {@code chatWebSocketTaskScheduler} 이름으로
 * 정확히 하나의 {@link org.springframework.scheduling.TaskScheduler} bean을 주입받아 부팅되는지 검증한다.
 *
 * <p>같은 컨텍스트에 {@code RedisChatRealtimeListenerConfiguration}이 등록하는
 * {@code chatRealtimeSubscriptionRetryScheduler}라는 또 다른 TaskScheduler bean이 함께 존재하므로, 타입만으로
 * 주입하면 {@code NoUniqueBeanDefinitionException}이 난다. Spring의 파라미터명-빈이름 일치 규칙에 기대는 이 요구사항이
 * 실제로 지켜지는지 전체 컨텍스트 부팅으로 확인한다.
 */
@Testcontainers
class MatchChatWebSocketProfileBootstrapPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final String REDIS_IMAGE = "redis:8.4-alpine";

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_match_chat_ws_bootstrap_test");

	@Container
	static final GenericContainer REDIS = new GenericContainer(REDIS_IMAGE)
		.withExposedPorts(6379)
		.waitingFor(Wait.forListeningPort());

	@Nested
	@ActiveProfiles("local")
	@SpringBootTest(classes = AlbamMateApplication.class, properties = {
		"app.security.cookie.secure=false",
		"app.notification.relay.enabled=false"
	})
	class LocalProfileTest {

		@Autowired
		private MatchChatWebSocketHandler matchChatWebSocketHandler;

		@DynamicPropertySource
		static void localProperties(DynamicPropertyRegistry registry) {
			registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
			registry.add("spring.datasource.username", POSTGRES::getUsername);
			registry.add("spring.datasource.password", POSTGRES::getPassword);
			registry.add("app.redis.host", REDIS::getHost);
			registry.add("app.redis.port", () -> REDIS.getMappedPort(6379));
		}

		@Test
		void local_프로필은_TaskScheduler_bean_충돌_없이_MatchChatWebSocketHandler를_부팅한다() {
			assertNotNull(matchChatWebSocketHandler);
		}
	}

	@Nested
	@ActiveProfiles("production")
	@SpringBootTest(classes = AlbamMateApplication.class, properties = {
		"app.security.cookie.secure=false",
		"app.notification.relay.enabled=false"
	})
	class ProductionProfileTest {

		@Autowired
		private MatchChatWebSocketHandler matchChatWebSocketHandler;

		@DynamicPropertySource
		static void productionProperties(DynamicPropertyRegistry registry) {
			registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
			registry.add("spring.datasource.username", POSTGRES::getUsername);
			registry.add("spring.datasource.password", POSTGRES::getPassword);
			registry.add("spring.data.redis.host", REDIS::getHost);
			registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
			registry.add("app.redis.host", REDIS::getHost);
			registry.add("app.redis.port", () -> REDIS.getMappedPort(6379));
			registry.add("app.monitoring.upstream-role", () -> "app1");
		}

		@Test
		void production_프로필은_TaskScheduler_bean_충돌_없이_MatchChatWebSocketHandler를_부팅한다() {
			assertNotNull(matchChatWebSocketHandler);
		}
	}
}
