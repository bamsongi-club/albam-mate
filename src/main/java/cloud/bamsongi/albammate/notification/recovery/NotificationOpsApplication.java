package cloud.bamsongi.albammate.notification.recovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import cloud.bamsongi.albammate.global.config.TimeConfig;
import cloud.bamsongi.albammate.notification.entity.NotificationOutboxEvent;
import cloud.bamsongi.albammate.notification.entity.NotificationOutboxRecipient;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxEventRepository;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxRecipientRepository;

/** 웹·일반 scheduler를 스캔하지 않는 notification-ops 전용 최소 애플리케이션이다. */
@SpringBootConfiguration
@Profile("notification-ops")
@EnableAutoConfiguration
@EntityScan(basePackageClasses = {NotificationOutboxEvent.class, NotificationOutboxRecipient.class})
@EnableJpaRepositories(basePackageClasses = {NotificationOutboxEventRepository.class,
	NotificationOutboxRecipientRepository.class})
@ComponentScan(basePackageClasses = NotificationOutboxRecoveryService.class, excludeFilters = {
	@ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
	@ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
	@ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = NotificationOpsApplication.class)
})
@Import(TimeConfig.class)
public class NotificationOpsApplication {

	@Bean
	NotificationOpsRunner notificationOpsRunner(
		NotificationOutboxRecoveryService recoveryService,
		Environment environment) {
		return new NotificationOpsRunner(recoveryService, environment);
	}

	/** one-shot 실행은 Servlet 웹 서버를 만들지 않는 독립 SpringApplication으로 시작한다. */
	public static SpringApplication create() {
		SpringApplication application = new SpringApplication(NotificationOpsApplication.class);
		application.setWebApplicationType(WebApplicationType.NONE);
		return application;
	}
}
