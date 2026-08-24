package cloud.bamsongi.albammate.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ChatMessageLimitPropertiesBindingTest {

	@Test
	void T1_기본_메시지_길이_제약은_API_계약과_같다() {
		contextRunnerWith().run(context -> {
			assertNull(context.getStartupFailure());
			ChatMessageLimitProperties properties = context.getBean(ChatMessageLimitProperties.class);
			assertEquals(100, properties.getMaxClientMessageIdLength());
			assertEquals(500, properties.getMaxContentLength());
		});
	}

	@Test
	void T5_API_상한을_벗어난_메시지_길이는_바인딩_실패로_끝난다() {
		assertBindingFailure("app.chat.message.max-client-message-id-length=0");
		assertBindingFailure("app.chat.message.max-content-length=0");
		assertBindingFailure("app.chat.message.max-client-message-id-length=101");
		assertBindingFailure("app.chat.message.max-content-length=501");
	}

	private ApplicationContextRunner contextRunnerWith(String... properties) {
		return new ApplicationContextRunner()
			.withUserConfiguration(ChatMessageLimitConfig.class)
			.withPropertyValues(properties);
	}

	private void assertBindingFailure(String property) {
		contextRunnerWith(property).run(context -> assertNotNull(context.getStartupFailure()));
	}
}
