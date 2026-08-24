package cloud.bamsongi.albammate.migration;

import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;

/** one-shot migrator가 schema 검증 성공 뒤에만 migration을 실행하게 하는 Boot 전략이다. */
public class FlywayValidateThenMigrateStrategy implements FlywayMigrationStrategy {

	@Override
	public void migrate(Flyway flyway) {
		flyway.validate();
		flyway.migrate();
	}
}
