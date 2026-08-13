package cloud.bamsongi.albammate.measurement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class AuthNotificationK6SourceContractTest {

	private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath();

	@Test
	void 알림_캠페인은_계획된_고정_순서대로_0점5배부터_실행할_수_있다() throws IOException {
		String mixedLoad = Files.readString(
			REPOSITORY_ROOT.resolve("load-tests/k6/jiho/mixed-load-capacity.js"));

		assertThat(mixedLoad)
			.contains("if (value === '0.5')")
			.doesNotContain("MIXED_HALF_SCALE_ACK")
			.doesNotContain("one-x-failed");
	}
}
