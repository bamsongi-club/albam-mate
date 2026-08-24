package cloud.bamsongi.albammate.user.service;

import java.io.InputStream;

/** 프로필 이미지 파일의 저장과 삭제를 추상화한다. */
public interface ProfileImageStorage {

	/**
	 * 프로필 이미지를 저장하고 접근 가능한 URL을 반환한다.
	 *
	 * @param userId 사용자 ID
	 * @param inputStream 이미지 데이터
	 * @param originalFilename 원본 파일명
	 * @param contentType MIME 타입
	 * @return 저장된 이미지의 접근 URL
	 */
	String store(long userId, InputStream inputStream, String originalFilename, String contentType);

	/** 이전 프로필 이미지를 삭제한다. URL이 null이거나 로컬 파일이 아니면 무시한다. */
	void delete(String imageUrl);
}
