package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class MatchPartyRepositoryStructureTest {

	@Test
	void lifecycle_후보_조회는_chat_저장_테이블을_직접_조회하지_않는다() throws IOException {
		String source = Files.readString(Path.of(
			"src/main/java/cloud/bamsongi/albammate/matching/repository/MatchPartyRepository.java"));

		assertFalse(source.contains("match_chat_rooms"));
		assertFalse(source.contains("match_chat_messages"));
	}
}
