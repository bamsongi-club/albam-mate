package cloud.bamsongi.albammate.user.service;

import java.util.Optional;

/** 클라이언트가 선언한 파일명·MIME 타입을 신뢰하지 않고, 실제 바이트의 매직 넘버로 이미지 형식을 판별한다. */
final class ImageFormatSniffer {

	private static final byte[] PNG_SIGNATURE = {(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
	private static final byte[] JPEG_SIGNATURE = {(byte)0xFF, (byte)0xD8, (byte)0xFF};
	private static final byte[] RIFF_SIGNATURE = {0x52, 0x49, 0x46, 0x46};
	private static final byte[] WEBP_SIGNATURE = {0x57, 0x45, 0x42, 0x50};

	private ImageFormatSniffer() {}

	/** 인식된 이미지 형식이면 서버가 고정한 확장자를, 아니면 빈 값을 반환한다. */
	static Optional<String> detectExtension(byte[] content) {
		if (matches(content, 0, PNG_SIGNATURE)) {
			return Optional.of(".png");
		}
		if (matches(content, 0, JPEG_SIGNATURE)) {
			return Optional.of(".jpg");
		}
		if (matches(content, 0, RIFF_SIGNATURE) && matches(content, 8, WEBP_SIGNATURE)) {
			return Optional.of(".webp");
		}
		return Optional.empty();
	}

	private static boolean matches(byte[] content, int offset, byte[] signature) {
		if (content.length < offset + signature.length) {
			return false;
		}
		for (int i = 0; i < signature.length; i++) {
			if (content[offset + i] != signature[i]) {
				return false;
			}
		}
		return true;
	}
}
