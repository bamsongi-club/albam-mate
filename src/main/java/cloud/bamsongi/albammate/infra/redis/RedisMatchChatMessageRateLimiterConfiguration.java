package cloud.bamsongi.albammate.infra.redis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** {@link RedisMatchChatMessageRateLimiter}가 사용하는 MATCH 전송 제한 속성을 등록한다. */
@Configuration(proxyBeanMethods = false)
@Profile({"local", "production"})
@EnableConfigurationProperties(MatchChatMessageRateLimitProperties.class)
public class RedisMatchChatMessageRateLimiterConfiguration {

}
