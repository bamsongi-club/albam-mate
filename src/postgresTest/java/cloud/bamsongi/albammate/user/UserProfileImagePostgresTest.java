package cloud.bamsongi.albammate.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;
import cloud.bamsongi.albammate.user.service.ProfileImageStorage;
import cloud.bamsongi.albammate.user.service.UserProfileService;

@Testcontainers
@SpringBootTest
@Import(UserProfileImagePostgresTest.ProfileImageStorageConfiguration.class)
class UserProfileImagePostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final long WAIT_SECONDS = 10;

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_user_profile_image_test");

	@Autowired
	private UserProfileService userProfileService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private RecordingProfileImageStorage profileImageStorage;

	@AfterEach
	void tearDown() {
		userRepository.deleteAll();
		profileImageStorage.clear();
	}

	@Test
	void 같은_사용자의_동시_업로드와_삭제는_새_파일_고아_없이_직렬화된다() throws Exception {
		User user = User.create("profile-image@example.com", "{bcrypt}hash", "프로필 사용자");
		user.changeProfileImageUrl("old-url");
		user = userRepository.saveAndFlush(user);
		profileImageStorage.add("old-url");
		long userId = user.getId();
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> upload = executor.submit(() -> {
				ready.countDown();
				await(start);
				userProfileService.uploadProfileImage(
					userId, InputStream.nullInputStream(), "new.png", "image/png");
			});
			Future<?> remove = executor.submit(() -> {
				ready.countDown();
				await(start);
				userProfileService.removeProfileImage(userId);
			});
			assertTrue(ready.await(WAIT_SECONDS, TimeUnit.SECONDS));
			start.countDown();
			upload.get(WAIT_SECONDS, TimeUnit.SECONDS);
			remove.get(WAIT_SECONDS, TimeUnit.SECONDS);

			String storedUrl = userRepository.findById(userId).orElseThrow().getProfileImageUrl();
			assertEquals(storedUrl == null ? Set.of() : Set.of(storedUrl), profileImageStorage.storedUrls());
			assertTrue(!profileImageStorage.storedUrls().contains("old-url"));
		} finally {
			start.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void 파일_저장_동안_동시_삭제는_사용자_행_잠금을_점유하지_않는다() throws Exception {
		User user = User.create("profile-image-boundary@example.com", "{bcrypt}hash", "경계 사용자");
		user.changeProfileImageUrl("old-url");
		user = userRepository.saveAndFlush(user);
		profileImageStorage.add("old-url");
		profileImageStorage.blockNextStore();
		long userId = user.getId();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> upload = executor.submit(
				() -> userProfileService.uploadProfileImage(
					userId, InputStream.nullInputStream(), "new.png", "image/png"));
			profileImageStorage.awaitBlockedStore();

			Future<?> remove = executor.submit(() -> userProfileService.removeProfileImage(userId));
			remove.get(WAIT_SECONDS, TimeUnit.SECONDS);
			profileImageStorage.releaseBlockedStore();
			upload.get(WAIT_SECONDS, TimeUnit.SECONDS);

			String storedUrl = userRepository.findById(userId).orElseThrow().getProfileImageUrl();
			assertTrue(storedUrl != null);
			assertEquals(Set.of(storedUrl), profileImageStorage.storedUrls());
		} finally {
			profileImageStorage.releaseBlockedStore();
			executor.shutdownNow();
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "동시성 동기화 지점에 도달하지 못했습니다.");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("동시성 대기 중 인터럽트되었습니다.", exception);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ProfileImageStorageConfiguration {

		@Bean
		@Primary
		RecordingProfileImageStorage profileImageStorage() {
			return new RecordingProfileImageStorage();
		}
	}

	static class RecordingProfileImageStorage implements ProfileImageStorage {

		private final AtomicLong sequence = new AtomicLong();
		private final Set<String> storedUrls = ConcurrentHashMap.newKeySet();
		private volatile CountDownLatch storeStarted = new CountDownLatch(0);
		private volatile CountDownLatch releaseStore = new CountDownLatch(0);

		@Override
		public String store(long userId, InputStream inputStream, String originalFilename, String contentType) {
			String url = "new-url-" + sequence.incrementAndGet();
			storedUrls.add(url);
			if (storeStarted.getCount() > 0) {
				storeStarted.countDown();
				await(releaseStore);
			}
			return url;
		}

		@Override
		public void delete(String imageUrl) {
			storedUrls.remove(imageUrl);
		}

		void add(String imageUrl) {
			storedUrls.add(imageUrl);
		}

		void blockNextStore() {
			storeStarted = new CountDownLatch(1);
			releaseStore = new CountDownLatch(1);
		}

		void awaitBlockedStore() {
			await(storeStarted);
		}

		void releaseBlockedStore() {
			releaseStore.countDown();
		}

		Set<String> storedUrls() {
			return Set.copyOf(storedUrls);
		}

		void clear() {
			storedUrls.clear();
		}
	}
}
