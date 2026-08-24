package cloud.bamsongi.albammate.migration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

/** 웹·업무 component scan 없이 Flyway 자동 설정만 시작하는 전용 one-shot 애플리케이션이다. */
@SpringBootConfiguration
@Profile("migrator")
@EnableAutoConfiguration
public class FlywayMigratorApplication {

	@Bean
	FlywayMigrationStrategy flywayMigrationStrategy() {
		return new FlywayValidateThenMigrateStrategy();
	}

	/** migrator는 HTTP server, scheduler 또는 일반 application runner를 시작하지 않는다. */
	public static SpringApplication create() {
		SpringApplication application = new SpringApplication(FlywayMigratorApplication.class);
		application.setWebApplicationType(WebApplicationType.NONE);
		return application;
	}
}
