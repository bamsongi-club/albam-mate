package cloud.bamsongi.albammate.measurement;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

import io.micrometer.core.instrument.MeterRegistry;

/** 측정 배포 외에는 계측 bean 자체를 만들지 않는다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthNotificationMeasurementProperties.class)
@ConditionalOnProperty(prefix = "app.measurement.auth-notification", name = "enabled", havingValue = "true")
public class AuthNotificationMeasurementConfiguration {

	@Bean
	AuthNotificationMeasurementRecorder authNotificationMeasurementRecorder(MeterRegistry meterRegistry) {
		return new AuthNotificationMeasurementRecorder(meterRegistry);
	}

	@Bean
	BeanPostProcessor sessionRepositoryMeasurementPostProcessor(
		AuthNotificationMeasurementRecorder measurementRecorder) {
		return new BeanPostProcessor() {
			@Override
			@SuppressWarnings({"rawtypes", "unchecked"})
			public Object postProcessAfterInitialization(Object bean, String beanName) {
				if (bean instanceof SessionRepository repository) {
					return new MeasurementSessionRepository<Session>(repository, measurementRecorder);
				}
				return bean;
			}
		};
	}
}
