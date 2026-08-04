package cloud.bamsongi.albammate.infra.redis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.security.jackson.SecurityJacksonModules;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/** Redis 프로필의 Spring Session 연결, 직렬화와 저장소를 구성하는 기술 어댑터다. */
@Configuration(proxyBeanMethods = false)
@Profile("local-multi")
@EnableConfigurationProperties(RedisSessionProperties.class)
@Import(RedisSessionConfiguration.LocalMultiSessionRepositoryConfiguration.class)
public class RedisSessionConfiguration {

	static final String LOCAL_MULTI_SESSION_NAMESPACE = "albam-mate:local-multi:session";
	private static final int SESSION_TTL_SECONDS = 30 * 60;

	@Bean
	LettuceConnectionFactory redisConnectionFactory(RedisSessionProperties properties) {
		return new LettuceConnectionFactory(properties.host(), properties.port());
	}

	@Bean(name = "springSessionDefaultRedisSerializer")
	RedisSerializer<Object> springSessionDefaultRedisSerializer() {
		BasicPolymorphicTypeValidator.Builder typeValidator = BasicPolymorphicTypeValidator.builder()
			.allowIfSubType(CurrentUserPrincipal.class);
		tools.jackson.databind.ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder()
			.addModules(SecurityJacksonModules.getModules(getClass().getClassLoader(), typeValidator))
			.addMixIn(CurrentUserPrincipal.class, CurrentUserPrincipalMixin.class)
			.build();
		return new GenericJacksonJsonRedisSerializer(mapper);
	}

	@Configuration(proxyBeanMethods = false)
	@Profile("local-multi")
	@EnableRedisHttpSession(redisNamespace = LOCAL_MULTI_SESSION_NAMESPACE, maxInactiveIntervalInSeconds = SESSION_TTL_SECONDS)
	static class LocalMultiSessionRepositoryConfiguration {}

}
