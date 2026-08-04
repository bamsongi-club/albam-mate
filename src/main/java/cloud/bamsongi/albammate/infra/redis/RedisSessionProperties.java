package cloud.bamsongi.albammate.infra.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Redis가 필요한 프로필에서만 사용하는 외부 연결 설정이다. */
@ConfigurationProperties("app.redis")
record RedisSessionProperties(String host, int port) {
}
