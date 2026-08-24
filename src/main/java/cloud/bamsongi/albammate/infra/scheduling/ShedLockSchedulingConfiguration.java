package cloud.bamsongi.albammate.infra.scheduling;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;

/** PostgreSQL 시계를 기준으로 ShedLock JDBC Provider를 구성한다. */
@Configuration(proxyBeanMethods = false)
class ShedLockSchedulingConfiguration {

	@Bean
	LockProvider shedLockProvider(DataSource dataSource) {
		return new JdbcTemplateLockProvider(
			JdbcTemplateLockProvider.Configuration.builder()
				.withJdbcTemplate(new JdbcTemplate(dataSource))
				.usingDbTime()
				.build());
	}
}
