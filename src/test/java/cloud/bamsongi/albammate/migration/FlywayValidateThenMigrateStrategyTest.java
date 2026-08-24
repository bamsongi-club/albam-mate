package cloud.bamsongi.albammate.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

class FlywayValidateThenMigrateStrategyTest {

	@Test
	void validate_후_migrate를_정확히_한번_호출한다() {
		Flyway flyway = Mockito.mock(Flyway.class);

		new FlywayValidateThenMigrateStrategy().migrate(flyway);

		InOrder calls = Mockito.inOrder(flyway);
		calls.verify(flyway).validate();
		calls.verify(flyway).migrate();
		calls.verifyNoMoreInteractions();
	}
}
