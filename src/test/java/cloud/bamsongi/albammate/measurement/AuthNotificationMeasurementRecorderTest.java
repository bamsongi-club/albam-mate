package cloud.bamsongi.albammate.measurement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AuthNotificationMeasurementRecorderTest {

	@Test
	void T1_측정_비활성_기본값은_계측기를_등록하지_않는다() {
		new ApplicationContextRunner()
			.withUserConfiguration(AuthNotificationMeasurementConfiguration.class)
			.withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
			.run(context -> {
				assertNull(context.getBeanProvider(AuthNotificationMeasurementRecorder.class).getIfAvailable());
				assertNull(context.getBeanProvider(org.springframework.beans.factory.config.BeanPostProcessor.class)
					.orderedStream().filter(bean -> bean.getClass().getName().contains("Measurement")).findFirst()
					.orElse(null));
			});
	}

	@Test
	void T2_T6_인증_단계와_거절_원인을_식별자_없이_기록한다() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		AuthNotificationMeasurementRecorder recorder = new AuthNotificationMeasurementRecorder(registry);

		recorder.authStage("bcrypt-verify", () -> {});
		recorder.authStage("session-context-save", () -> {});
		recorder.authRejection("bcrypt-slot");

		assertEquals(1, registry.find("auth.login.stage.duration").tag("stage", "bcrypt-verify").timer().count());
		assertEquals(1, registry.find("auth.login.rejections").tag("source", "bcrypt-slot").counter().count());
		assertSafeTags(registry);
	}

	@Test
	void T7_T9_알림_조회와_relay_단계를_분리해_기록한다() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		AuthNotificationMeasurementRecorder recorder = new AuthNotificationMeasurementRecorder(registry);

		recorder.queryStage("content", () -> 1);
		recorder.queryStage("total-count", () -> 1);
		recorder.queryStage("unread-count", () -> 1);
		recorder.relayStage("claim", "success", () -> {});
		recorder.relayStage("afterCompletion", "rolled-back", () -> {});

		assertEquals(1, registry.find("notification.query.stage.duration").tag("stage", "content").timer().count());
		assertEquals(1,
			registry.find("notification.query.stage.duration").tag("stage", "unread-count").timer().count());
		assertEquals(1,
			registry.find("notification.relay.stage.duration").tag("result", "rolled-back").timer().count());
		assertSafeTags(registry);
	}

	@Test
	void T5_위임형_세션_저장소는_save만_기록하고_나머지_의미를_보존한다() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		MapSessionRepository delegate = new MapSessionRepository(new java.util.concurrent.ConcurrentHashMap<>());
		MeasurementSessionRepository<MapSession> repository = new MeasurementSessionRepository<>(delegate,
			new AuthNotificationMeasurementRecorder(registry));
		MapSession session = repository.createSession();

		repository.save(session);

		assertEquals(session.getId(), repository.findById(session.getId()).getId());
		assertEquals(1,
			registry.find("auth.login.stage.duration").tag("stage", "session-repository-save").timer().count());
	}

	@Test
	void T10_미실행_optional_단계와_승인된_tag_집합을_0값으로_선등록한다() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		new AuthNotificationMeasurementRecorder(registry);

		assertEquals(0,
			registry.find("auth.login.stage.duration").tag("stage", "bcrypt-upgrade-encode").timer().count());
		assertEquals(0, registry.find("auth.login.rejections").tag("source", "redis-unavailable").counter().count());
		assertEquals(0,
			registry.find("notification.query.stage.duration").tag("stage", "unread-count").timer().count());
		assertEquals(0, registry.find("notification.relay.stage.duration")
			.tags("stage", "tx-total", "result", "rolled-back").timer().count());
		assertEquals(0, registry.find("notification.relay.stage.duration")
			.tags("stage", "afterCompletion", "result", "committed").timer().count());
		AuthNotificationMeasurementRecorder recorder = new AuthNotificationMeasurementRecorder(registry);
		assertThrows(IllegalArgumentException.class, () -> recorder.authStage("email", () -> {}));
		assertThrows(IllegalArgumentException.class, () -> recorder.authRejection("user-id"));
		assertThrows(IllegalArgumentException.class, () -> recorder.relayStage("tx-secret", "committed", () -> {}));
		assertSafeTags(registry);
	}

	private void assertSafeTags(SimpleMeterRegistry registry) {
		assertTrue(registry.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
			.noneMatch(tag -> tag.getKey().matches(".*(email|user|run|session|id).*")));
	}
}
