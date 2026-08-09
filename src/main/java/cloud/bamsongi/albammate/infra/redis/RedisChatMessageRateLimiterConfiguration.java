package cloud.bamsongi.albammate.infra.redis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** {@link RedisChatMessageRateLimiter}가 사용하는 전송 제한 속성을 등록한다. */
@Configuration(proxyBeanMethods = false)
@Profile({"local", "production"})
@EnableConfigurationProperties(ChatMessageRateLimitProperties.class)
public class RedisChatMessageRateLimiterConfiguration {

}
