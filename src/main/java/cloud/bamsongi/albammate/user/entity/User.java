package cloud.bamsongi.albammate.user.entity;

import java.util.Objects;

import cloud.bamsongi.albammate.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "users")
public class User extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Column(name = "email", unique = true, length = 255)
	private String email;

	@Column(name = "password_hash", length = 255)
	private String passwordHash;

	@Column(name = "nickname", nullable = false, length = 50)
	private String nickname;

	@Column(name = "profile_image_url", length = 2048)
	private String profileImageUrl;

	private User(String email, String passwordHash, String nickname, String profileImageUrl) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.nickname = Objects.requireNonNull(nickname, "nickname");
		this.profileImageUrl = profileImageUrl;
	}

	public static User create(String email, String passwordHash, String nickname) {
		return new User(
			Objects.requireNonNull(email, "email"),
			Objects.requireNonNull(passwordHash, "passwordHash"),
			nickname,
			null);
	}

	/** 소셜 첫 로그인에서 선택 이메일과 비밀번호 없는 사용자를 만든다. */
	public static User createSocial(String email, String nickname, String profileImageUrl) {
		return new User(email, null, nickname, profileImageUrl);
	}

	/** 로그인 성공 뒤 비용 상향이 필요한 경우에만 저장 해시를 교체한다. */
	public void changePasswordHash(String passwordHash) {
		this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
	}

	/** 프로필 수정에서 검증·정규화된 닉네임으로 현재 사용자의 표시 이름을 바꾼다. */
	public void changeNickname(String nickname) {
		this.nickname = Objects.requireNonNull(nickname, "nickname");
	}

	/** 프로필 이미지 URL을 교체하거나 새로 설정한다. null이면 이미지를 지운다. */
	public void changeProfileImageUrl(String profileImageUrl) {
		this.profileImageUrl = profileImageUrl;
	}
}
