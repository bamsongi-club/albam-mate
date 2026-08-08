package cloud.bamsongi.albammate.infra.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ChatMessageRateLimitPropertiesBindingTest {

	@Test
	void T3_기본_전송_제한_속성은_계약과_같다() {
		contextRunnerWith().run(context -> {
			assertNull(context.getStartupFailure());
			ChatMessageRateLimitProperties properties = context.getBean(ChatMessageRateLimitProperties.class);
			assertEquals(5, properties.userLimit());
			assertEquals(30, properties.roomLimit());
			assertEquals(Duration.ofSeconds(10), properties.window());
		});
	}

	@Test
	void T5_실제_소비_단위보다_짧은_전송_제한_창은_바인딩_실패로_끝난다() {
		assertBindingFailure("app.chat.rate-limit.user-limit=0");
		assertBindingFailure("app.chat.rate-limit.room-limit=0");
		assertBindingFailure("app.chat.rate-limit.window=0s");
		assertBindingFailure("app.chat.rate-limit.window=999us");
	}

	private ApplicationContextRunner contextRunnerWith(String... properties) {
		return new ApplicationContextRunner()
			.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
			.withUserConfiguration(RedisChatMessageRateLimiterConfiguration.class)
			.withPropertyValues(properties);
	}

	private void assertBindingFailure(String property) {
		contextRunnerWith(property).run(context -> assertNotNull(context.getStartupFailure()));
	}
}
