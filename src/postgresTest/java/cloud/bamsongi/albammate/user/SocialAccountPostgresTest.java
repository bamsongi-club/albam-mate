package cloud.bamsongi.albammate.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.user.contract.SocialAccountService;
import cloud.bamsongi.albammate.user.contract.SocialIdentity;
import cloud.bamsongi.albammate.user.contract.SocialLinkResult;
import cloud.bamsongi.albammate.user.contract.SocialLoginResult;
import cloud.bamsongi.albammate.user.contract.SocialProvider;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import cloud.bamsongi.albammate.user.repository.SocialAccountRepository;
import cloud.bamsongi.albammate.user.repository.UserRepository;

@Testcontainers
@SpringBootTest
@Import(SocialAccountPostgresTest.SocialAccountConcurrencyConfiguration.class)
class SocialAccountPostgresTest {

	private static final org.testcontainers.utility.DockerImageName POSTGRES_IMAGE = cloud.bamsongi.albammate.testsupport.PgVectorPostgresImages
		.postgres18();

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_social_account_test");

	@Autowired
	private Flyway flyway;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private SocialAccountService socialAccountService;

	@Autowired
	private SocialAccountRepository socialAccountRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private SocialIdentityReadGate socialIdentityReadGate;

	@Test
	void V10은_기존_사용자를_보존하고_사용자와_소셜_계정_제약을_생성한다() {
		String schemaName = "social_v10_" + UUID.randomUUID().toString().replace("-", "");
		try {
			migrate(schemaName, "8");
			jdbcTemplate.update(
				"insert into " + schemaName + ".users "
					+ "(email, password_hash, nickname, created_at, updated_at) "
					+ "values ('legacy@example.com', '{bcrypt}legacy', '기존 사용자', "
					+ "TIMESTAMPTZ '2026-08-04T00:00:00Z', TIMESTAMPTZ '2026-08-04T00:00:00Z')");

			migrate(schemaName, null);

			assertEquals(
				1,
				jdbcTemplate.queryForObject(
					"select count(*) from " + schemaName + ".users where email = 'legacy@example.com' "
						+ "and password_hash = '{bcrypt}legacy'",
					Integer.class));
			assertEquals("YES", nullable(schemaName, "users", "email"));
			assertEquals("YES", nullable(schemaName, "users", "password_hash"));
			assertEquals("NO", nullable(schemaName, "social_accounts", "user_id"));
			assertEquals("NO", nullable(schemaName, "social_accounts", "provider"));
			assertEquals("NO", nullable(schemaName, "social_accounts", "provider_subject"));
		} finally {
			jdbcTemplate.execute("drop schema if exists " + schemaName + " cascade");
		}
	}

	@Test
	void PostgreSQL은_소셜_계정_FK_CHECK와_두_유일_제약을_강제한다() {
		flyway.validate();
		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"select count(*) from information_schema.columns "
					+ "where table_schema = current_schema() "
					+ "and table_name in ('users', 'social_accounts') "
					+ "and column_name in ('access_token', 'refresh_token', 'id_token', "
					+ "'authorization_code', 'client_secret')",
				Integer.class));
		long firstUserId = insertUser(unique("first") + "@example.com");
		long secondUserId = insertUser(unique("second") + "@example.com");
		String subject = unique("subject");
		insertSocialAccount(firstUserId, "GOOGLE", subject);

		assertConstraint("23503", "fk_social_accounts_user",
			() -> insertSocialAccount(Long.MAX_VALUE, "NAVER", unique("missing-user")));
		assertConstraint("23514", "ck_social_accounts_provider",
			() -> insertSocialAccount(secondUserId, "UNSUPPORTED", unique("unsupported")));
		assertConstraint("23505", "uq_social_accounts_provider_subject",
			() -> insertSocialAccount(secondUserId, "GOOGLE", subject));
		assertConstraint("23505", "uq_social_accounts_user_provider",
			() -> insertSocialAccount(firstUserId, "GOOGLE", unique("replacement")));
		assertConstraint("23514", "ck_users_password_hash_requires_email",
			() -> jdbcTemplate.update(
				"insert into users (email, password_hash, nickname, created_at, updated_at) values "
					+ "(null, '{bcrypt}invalid', '잘못된 자격증명', now(), now())"));
	}

	@Test
	void PostgreSQL의_동시_첫_로그인은_한_사용자와_한_연결로_수렴한다() throws Exception {
		String subject = unique("concurrent");
		SocialIdentity identity = new SocialIdentity(
			SocialProvider.KAKAO,
			subject,
			Optional.empty(),
			Optional.of(UserNickname.from("동시 소셜 사용자").orElseThrow()),
			Optional.empty());
		long usersBefore = userRepository.count();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		socialIdentityReadGate.arm(SocialProvider.KAKAO, subject);
		try {
			Future<SocialLoginResult> first = executor.submit(() -> socialAccountService.login(identity));
			Future<SocialLoginResult> second = executor.submit(() -> socialAccountService.login(identity));

			assertEquals(loggedIn(first.get(15, TimeUnit.SECONDS)).account(),
				loggedIn(second.get(15, TimeUnit.SECONDS)).account());
			assertEquals(2, socialIdentityReadGate.absentIdentityReadCount());
			assertEquals(usersBefore + 1, userRepository.count());
			assertEquals(
				1,
				socialAccountRepository.findByProviderAndProviderSubject(SocialProvider.KAKAO, subject).stream()
					.count());
		} finally {
			socialIdentityReadGate.disarm();
			executor.shutdownNow();
		}
	}

	@Test
	void PostgreSQL에서_두_사용자의_같은_외부_신원_동시_연결은_기존_소유자를_덮어쓰지_않는다() throws Exception {
		long firstUserId = insertUser(unique("first-link") + "@example.com");
		long secondUserId = insertUser(unique("second-link") + "@example.com");
		String subject = unique("shared-link");
		SocialIdentity identity = new SocialIdentity(
			SocialProvider.GOOGLE,
			subject,
			Optional.empty(),
			Optional.empty(),
			Optional.empty());
		ExecutorService executor = Executors.newFixedThreadPool(2);
		socialIdentityReadGate.armLink(
			SocialProvider.GOOGLE,
			List.of(subject, subject),
			List.of(firstUserId, secondUserId));
		try {
			Future<SocialLinkResult> first = executor.submit(() -> socialAccountService.link(firstUserId, identity));
			Future<SocialLinkResult> second = executor.submit(() -> socialAccountService.link(secondUserId, identity));

			SocialLinkResult firstResult = first.get(15, TimeUnit.SECONDS);
			SocialLinkResult secondResult = second.get(15, TimeUnit.SECONDS);
			assertOneLinkedAndOneConflict(firstResult, secondResult);
			assertEquals(2, socialIdentityReadGate.absentIdentityReadCount());
			assertEquals(2, socialIdentityReadGate.absentUserProviderReadCount());
			assertEquals(1, socialIdentityReadGate.postGateIdentityReadCount());
			assertEquals(0, socialIdentityReadGate.postGateUserProviderReadCount());
			assertEquals(
				firstResult == SocialLinkResult.LINKED ? firstUserId : secondUserId,
				socialAccountOwner(SocialProvider.GOOGLE, subject));
		} finally {
			socialIdentityReadGate.disarm();
			executor.shutdownNow();
		}
	}

	@Test
	void PostgreSQL에서_같은_사용자의_같은_외부_신원_동시_연결은_한_행으로_수렴한다() throws Exception {
		long userId = insertUser(unique("same-identity-link") + "@example.com");
		String subject = unique("same-subject");
		SocialIdentity identity = new SocialIdentity(
			SocialProvider.GOOGLE,
			subject,
			Optional.empty(),
			Optional.empty(),
			Optional.empty());
		ExecutorService executor = Executors.newFixedThreadPool(2);
		socialIdentityReadGate.armLink(
			SocialProvider.GOOGLE,
			List.of(subject, subject),
			List.of(userId, userId));
		try {
			Future<SocialLinkResult> first = executor.submit(() -> socialAccountService.link(userId, identity));
			Future<SocialLinkResult> second = executor.submit(() -> socialAccountService.link(userId, identity));

			assertEquals(SocialLinkResult.LINKED, first.get(15, TimeUnit.SECONDS));
			assertEquals(SocialLinkResult.LINKED, second.get(15, TimeUnit.SECONDS));
			assertEquals(2, socialIdentityReadGate.absentIdentityReadCount());
			assertEquals(2, socialIdentityReadGate.absentUserProviderReadCount());
			assertEquals(1, socialIdentityReadGate.postGateIdentityReadCount());
			assertEquals(0, socialIdentityReadGate.postGateUserProviderReadCount());
			assertEquals(
				1,
				jdbcTemplate.queryForObject(
					"select count(*) from social_accounts where user_id = ? and provider = ? and provider_subject = ?",
					Integer.class,
					userId,
					SocialProvider.GOOGLE.name(),
					subject));
		} finally {
			socialIdentityReadGate.disarm();
			executor.shutdownNow();
		}
	}

	@Test
	void PostgreSQL에서_한_사용자의_같은_제공자_다른_외부_신원_동시_연결은_기존_연결을_보존한다() throws Exception {
		long userId = insertUser(unique("same-user-link") + "@example.com");
		String firstSubject = unique("first-subject");
		String secondSubject = unique("second-subject");
		SocialIdentity firstIdentity = new SocialIdentity(
			SocialProvider.NAVER,
			firstSubject,
			Optional.empty(),
			Optional.empty(),
			Optional.empty());
		SocialIdentity secondIdentity = new SocialIdentity(
			SocialProvider.NAVER,
			secondSubject,
			Optional.empty(),
			Optional.empty(),
			Optional.empty());
		ExecutorService executor = Executors.newFixedThreadPool(2);
		socialIdentityReadGate.armLink(
			SocialProvider.NAVER,
			List.of(firstSubject, secondSubject),
			List.of(userId, userId));
		try {
			Future<SocialLinkResult> first = executor.submit(() -> socialAccountService.link(userId, firstIdentity));
			Future<SocialLinkResult> second = executor.submit(() -> socialAccountService.link(userId, secondIdentity));

			SocialLinkResult firstResult = first.get(15, TimeUnit.SECONDS);
			SocialLinkResult secondResult = second.get(15, TimeUnit.SECONDS);
			assertOneLinkedAndOneConflict(firstResult, secondResult);
			assertEquals(2, socialIdentityReadGate.absentIdentityReadCount());
			assertEquals(2, socialIdentityReadGate.absentUserProviderReadCount());
			assertEquals(1, socialIdentityReadGate.postGateIdentityReadCount());
			assertEquals(1, socialIdentityReadGate.postGateUserProviderReadCount());
			assertEquals(
				firstResult == SocialLinkResult.LINKED ? firstSubject : secondSubject,
				jdbcTemplate.queryForObject(
					"select provider_subject from social_accounts where user_id = ? and provider = ?",
					String.class,
					userId,
					SocialProvider.NAVER.name()));
		} finally {
			socialIdentityReadGate.disarm();
			executor.shutdownNow();
		}
	}

	private void migrate(String schemaName, String target) {
		var configuration = Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration")
			.schemas(schemaName)
			.defaultSchema(schemaName);
		if (target != null) {
			configuration.target(target);
		}
		configuration.load().migrate();
	}

	private String nullable(String schemaName, String tableName, String columnName) {
		return jdbcTemplate.queryForObject(
			"select is_nullable from information_schema.columns "
				+ "where table_schema = ? and table_name = ? and column_name = ?",
			String.class,
			schemaName,
			tableName,
			columnName);
	}

	private long insertUser(String email) {
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, "
				+ "'{bcrypt}hash', 'PostgreSQL 사용자', now(), now()) returning id",
			Long.class,
			email);
	}

	private void insertSocialAccount(long userId, String provider, String subject) {
		jdbcTemplate.update(
			"insert into social_accounts (user_id, provider, provider_subject, created_at, updated_at) "
				+ "values (?, ?, ?, now(), now())",
			userId,
			provider,
			subject);
	}

	private long socialAccountOwner(SocialProvider provider, String subject) {
		return jdbcTemplate.queryForObject(
			"select user_id from social_accounts where provider = ? and provider_subject = ?",
			Long.class,
			provider.name(),
			subject);
	}

	private void assertOneLinkedAndOneConflict(SocialLinkResult first, SocialLinkResult second) {
		assertEquals(1, (first == SocialLinkResult.LINKED ? 1 : 0) + (second == SocialLinkResult.LINKED ? 1 : 0));
		assertEquals(
			1,
			(first == SocialLinkResult.LINK_CONFLICT ? 1 : 0)
				+ (second == SocialLinkResult.LINK_CONFLICT ? 1 : 0));
	}

	private void assertConstraint(
		String expectedSqlState,
		String expectedConstraint,
		org.junit.jupiter.api.function.Executable operation) {
		DataIntegrityViolationException exception = assertThrows(DataIntegrityViolationException.class, operation);
		SQLException sqlException = findSqlException(exception);

		assertEquals(expectedSqlState, sqlException.getSQLState());
		assertTrue(containsMessage(exception, expectedConstraint));
	}

	private SQLException findSqlException(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof SQLException sqlException) {
				return sqlException;
			}
		}
		throw new AssertionError("PostgreSQL SQLException 원인이 없습니다.", throwable);
	}

	private boolean containsMessage(Throwable throwable, String expectedText) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current.getMessage() != null && current.getMessage().contains(expectedText)) {
				return true;
			}
		}
		return false;
	}

	private SocialLoginResult.LoggedIn loggedIn(SocialLoginResult result) {
		return assertInstanceOf(SocialLoginResult.LoggedIn.class, result);
	}

	private String unique(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().replace("-", "");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class SocialAccountConcurrencyConfiguration {

		@Bean
		SocialIdentityReadGate socialIdentityReadGate() {
			return new SocialIdentityReadGate();
		}

		@Bean
		@Primary
		SocialAccountRepository gatedSocialAccountRepository(
			@Qualifier("socialAccountRepository") SocialAccountRepository delegate,
			SocialIdentityReadGate gate) {
			return (SocialAccountRepository)Proxy.newProxyInstance(
				SocialAccountRepository.class.getClassLoader(),
				new Class<?>[] {SocialAccountRepository.class},
				(proxy, method, arguments) -> invokeAfterAbsentIdentityRead(delegate, gate, method, arguments));
		}

		private Object invokeAfterAbsentIdentityRead(
			SocialAccountRepository delegate,
			SocialIdentityReadGate gate,
			Method method,
			Object[] arguments) throws Throwable {
			try {
				Object result = method.invoke(delegate, arguments);
				if ("findByProviderAndProviderSubject".equals(method.getName())
					&& result instanceof Optional<?> optional) {
					gate.awaitAfterIdentityRead((SocialProvider)arguments[0], (String)arguments[1], optional.isEmpty());
				}
				if ("findByUserIdAndProvider".equals(method.getName())
					&& result instanceof Optional<?> optional) {
					gate.awaitAfterUserProviderRead(
						(Long)arguments[0], (SocialProvider)arguments[1], optional.isEmpty());
				}
				return result;
			} catch (InvocationTargetException exception) {
				throw exception.getCause();
			}
		}
	}

	static final class SocialIdentityReadGate {

		private SocialProvider provider;
		private List<String> subjects;
		private List<Long> userIds;
		private CountDownLatch bothAbsentIdentityReads;
		private CountDownLatch bothAbsentUserProviderReads;
		private int absentIdentityReadCount;
		private int absentUserProviderReadCount;
		private int postGateIdentityReadCount;
		private int postGateUserProviderReadCount;

		synchronized void arm(SocialProvider provider, String subject) {
			arm(provider, List.of(subject, subject), List.of());
		}

		synchronized void armLink(SocialProvider provider, List<String> subjects, List<Long> userIds) {
			arm(provider, subjects, userIds);
		}

		private void arm(SocialProvider provider, List<String> subjects, List<Long> userIds) {
			this.provider = provider;
			this.subjects = subjects;
			this.userIds = userIds;
			bothAbsentIdentityReads = new CountDownLatch(subjects.size());
			bothAbsentUserProviderReads = userIds.isEmpty() ? null : new CountDownLatch(userIds.size());
			absentIdentityReadCount = 0;
			absentUserProviderReadCount = 0;
			postGateIdentityReadCount = 0;
			postGateUserProviderReadCount = 0;
		}

		void awaitAfterIdentityRead(SocialProvider provider, String subject, boolean absent) {
			CountDownLatch latch;
			synchronized (this) {
				if (bothAbsentIdentityReads == null || this.provider != provider || !subjects.contains(subject)) {
					return;
				}
				if (absentIdentityReadCount >= subjects.size()) {
					postGateIdentityReadCount++;
					return;
				}
				if (!absent) {
					throw new AssertionError("소셜 연결 요청이 외부 신원 부재 조회를 마치지 못했습니다.");
				}
				absentIdentityReadCount++;
				latch = bothAbsentIdentityReads;
			}
			latch.countDown();
			try {
				if (!latch.await(5, TimeUnit.SECONDS)) {
					throw new AssertionError("두 소셜 연결 요청이 모두 외부 신원 미존재 조회에 도달하지 못했습니다.");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("소셜 연결 외부 신원 조회 게이트를 기다리다 인터럽트되었습니다.", exception);
			}
		}

		void awaitAfterUserProviderRead(Long userId, SocialProvider provider, boolean absent) {
			CountDownLatch latch;
			synchronized (this) {
				if (bothAbsentUserProviderReads == null || this.provider != provider || !userIds.contains(userId)) {
					return;
				}
				if (absentUserProviderReadCount >= userIds.size()) {
					postGateUserProviderReadCount++;
					return;
				}
				if (!absent) {
					throw new AssertionError("소셜 연결 요청이 사용자 제공자 부재 조회를 마치지 못했습니다.");
				}
				absentUserProviderReadCount++;
				latch = bothAbsentUserProviderReads;
			}
			latch.countDown();
			try {
				if (!latch.await(5, TimeUnit.SECONDS)) {
					throw new AssertionError("두 소셜 연결 요청이 모두 사용자 제공자 미존재 조회에 도달하지 못했습니다.");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("소셜 연결 사용자 제공자 조회 게이트를 기다리다 인터럽트되었습니다.", exception);
			}
		}

		synchronized int absentIdentityReadCount() {
			return absentIdentityReadCount;
		}

		synchronized int absentUserProviderReadCount() {
			return absentUserProviderReadCount;
		}

		synchronized int postGateIdentityReadCount() {
			return postGateIdentityReadCount;
		}

		synchronized int postGateUserProviderReadCount() {
			return postGateUserProviderReadCount;
		}

		synchronized void disarm() {
			provider = null;
			subjects = null;
			userIds = null;
			bothAbsentIdentityReads = null;
			bothAbsentUserProviderReads = null;
		}
	}
}
