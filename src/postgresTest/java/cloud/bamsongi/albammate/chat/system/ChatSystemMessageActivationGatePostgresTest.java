package cloud.bamsongi.albammate.chat.system;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;

/**
 * #869 T6 — gate 판정이 {@code enabled_at} 전후 사건의 안내 유무를 실제 PostgreSQL {@code clock_timestamp()}로
 * 가르며, 애플리케이션 {@code Clock}과 무관함을 재현한다.
 */
@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
@Import(ChatSystemMessageActivationGatePostgresTest.WrongClockConfiguration.class)
class ChatSystemMessageActivationGatePostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final String GATE_NAME = "chat-system-message";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_chat_system_message_gate_test");

	@Autowired
	private ChatSystemMessageActivationGateRepository gateRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void T6_행이_없거나_enabled_at이_비어있으면_비활성으로_판정한다() {
		setEnabledAt(null);

		Optional<Boolean> active = gateRepository.isActiveNow(GATE_NAME);

		assertTrue(active.isEmpty());
	}

	@Test
	void T6_enabled_at이_DB_현재_시각보다_과거면_애플리케이션_Clock이_틀려도_활성으로_판정한다() {
		setEnabledAtRelativeToDbNow("-2 seconds");

		Optional<Boolean> active = gateRepository.isActiveNow(GATE_NAME);

		assertTrue(active.orElse(false));
	}

	@Test
	void T6_enabled_at이_DB_현재_시각보다_미래면_애플리케이션_Clock이_틀려도_비활성으로_판정한다() {
		setEnabledAtRelativeToDbNow("+1 hour");

		Optional<Boolean> active = gateRepository.isActiveNow(GATE_NAME);

		assertFalse(active.orElse(true));
	}

	private void setEnabledAt(Instant enabledAt) {
		jdbcTemplate.update(
			"update chat_system_message_activation set enabled_at = ?, updated_at = current_timestamp "
				+ "where gate_name = ?",
			enabledAt == null ? null : java.sql.Timestamp.from(enabledAt),
			GATE_NAME);
	}

	private void setEnabledAtRelativeToDbNow(String interval) {
		jdbcTemplate.update(
			"update chat_system_message_activation "
				+ "set enabled_at = clock_timestamp() + interval '" + interval + "', updated_at = current_timestamp "
				+ "where gate_name = ?",
			GATE_NAME);
	}

	/** gate 판정이 애플리케이션 Clock을 전혀 참조하지 않음을 증명하기 위해 일부러 틀린 시각을 고정한다. */
	@TestConfiguration(proxyBeanMethods = false)
	static class WrongClockConfiguration {

		@Bean
		@Primary
		Clock wrongClock() {
			return Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC);
		}
	}
}
