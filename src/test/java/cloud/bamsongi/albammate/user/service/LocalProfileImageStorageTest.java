package cloud.bamsongi.albammate.user.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalProfileImageStorageTest {

	@TempDir
	Path tempDir;

	private LocalProfileImageStorage storage;

	@BeforeEach
	void setUp() {
		storage = new LocalProfileImageStorage(tempDir.toString());
		storage.ensureDirectory();
	}

	@AfterEach
	void tearDown() throws IOException {
		try (Stream<Path> files = Files.walk(tempDir)) {
			files.filter(Files::isRegularFile).forEach(file -> {
				try {
					Files.delete(file);
				} catch (IOException e) {
					// 무시
				}
			});
		}
	}

	@Test
	void 정상적인_이미지를_저장하고_URL을_반환한다() throws Exception {
		ByteArrayInputStream in = new ByteArrayInputStream("dummy image content".getBytes());
		String url = storage.store(1L, in, "test.png", "image/png");

		assertTrue(url.startsWith("/uploads/profile/1_"));
		assertTrue(url.endsWith(".png"));

		String filename = url.substring("/uploads/profile/".length());
		assertTrue(Files.exists(tempDir.resolve(filename)));
	}

	@Test
	void 삭제_메서드는_URL을_받아_파일을_삭제한다() throws Exception {
		ByteArrayInputStream in = new ByteArrayInputStream("dummy image content".getBytes());
		String url = storage.store(1L, in, "test.jpg", "image/jpeg");

		String filename = url.substring("/uploads/profile/".length());
		Path savedFile = tempDir.resolve(filename);
		assertTrue(Files.exists(savedFile));

		assertDoesNotThrow(() -> storage.delete(url));
		assertTrue(Files.notExists(savedFile));
	}

	@Test
	void 유효하지_않은_URL삭제는_무시한다() {
		assertDoesNotThrow(() -> storage.delete(null));
		assertDoesNotThrow(() -> storage.delete("https://external.com/image.png"));
		assertDoesNotThrow(() -> storage.delete("/uploads/profile/../invalid.png"));
	}
}
