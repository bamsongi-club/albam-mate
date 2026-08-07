package cloud.bamsongi.albammate.user.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/** 로컬 디스크에 프로필 이미지를 저장한다. */
@Component
public class LocalProfileImageStorage implements ProfileImageStorage {

	private static final String SERVE_PATH_PREFIX = "/uploads/profile/";

	private final Path uploadDir;

	public LocalProfileImageStorage(
		@Value("${app.profile-image.upload-dir:./uploads/profile}")
		String uploadDir) {
		this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
	}

	@PostConstruct
	void ensureDirectory() {
		try {
			Files.createDirectories(uploadDir);
		} catch (IOException exception) {
			throw new UncheckedIOException("프로필 이미지 업로드 디렉토리를 생성할 수 없습니다.", exception);
		}
	}

	@Override
	public String store(long userId, InputStream inputStream, String originalFilename, String contentType) {
		byte[] content;
		try {
			content = inputStream.readAllBytes();
		} catch (IOException exception) {
			throw new UncheckedIOException("프로필 이미지를 읽을 수 없습니다.", exception);
		}
		String extension = ImageFormatSniffer.detectExtension(content)
			.orElseThrow(() -> new IllegalArgumentException("지원하지 않는 이미지 형식입니다."));
		String storedFilename = userId + "_" + UUID.randomUUID() + extension;
		Path target = uploadDir.resolve(storedFilename).normalize();
		if (!target.startsWith(uploadDir)) {
			throw new IllegalArgumentException("유효하지 않은 파일명입니다.");
		}
		try {
			Files.write(target, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		} catch (IOException exception) {
			throw new UncheckedIOException("프로필 이미지를 저장할 수 없습니다.", exception);
		}
		return SERVE_PATH_PREFIX + storedFilename;
	}

	@Override
	public void delete(String imageUrl) {
		if (imageUrl == null || !imageUrl.startsWith(SERVE_PATH_PREFIX)) {
			return;
		}
		String filename = imageUrl.substring(SERVE_PATH_PREFIX.length());
		Path file = uploadDir.resolve(filename).normalize();
		if (!file.startsWith(uploadDir)) {
			return;
		}
		try {
			Files.deleteIfExists(file);
		} catch (IOException exception) {
			// 이전 이미지 삭제 실패는 무시한다.
		}
	}
}
