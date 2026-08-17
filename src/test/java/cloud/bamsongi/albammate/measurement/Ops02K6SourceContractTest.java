package cloud.bamsongi.albammate.measurement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class Ops02K6SourceContractTest {

	private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath();

	@Test
	void T3_지연과_pool_대기는_같은_release의_분리된_통제_단계로만_실행한다() throws IOException {
		String scenario = scenario();

		assertThat(scenario)
			.contains("requireCapacityProfile()")
			.contains("'baseline', 'slow-request', 'db-pool-wait', 'recovery'")
			.contains("ALBAM_MATE_RELEASE")
			.contains("publicProbe(client")
			.contains("upstreamName(response)")
			.contains("phase: PHASE")
			.contains("release: RELEASE")
			.contains("ops02_request_errors: ['rate==0']")
			.contains("dropped_iterations: ['count==0']")
			.contains("'p(50)'", "'p(95)'", "'p(99)'")
			.doesNotContain("p(95)<=")
			.doesNotContainIgnoringCase("SLA");
	}

	@Test
	void T3_실행_안내는_주입과_관측을_분리하고_복구를_새_run으로_확인한다() throws IOException {
		assertThat(read("load-tests/k6/jiho/README.md"))
			.contains("ops02-latency-saturation.js")
			.contains("baseline → slow-request → recovery")
			.contains("baseline → db-pool-wait → recovery")
			.contains("ALBAM_MATE_RUN_ID=ops02-baseline-1")
			.contains("같은 `ALBAM_MATE_RELEASE`")
			.contains("주입은 인프라 운영 도구가 소유")
			.contains("build/k6/ops02-latency-saturation/")
			.contains("SLA나 최종 용량");
	}

	private String scenario() throws IOException {
		return read("load-tests/k6/jiho/ops02-latency-saturation.js");
	}

	private String read(String relativePath) throws IOException {
		return Files.readString(REPOSITORY_ROOT.resolve(relativePath));
	}
}
