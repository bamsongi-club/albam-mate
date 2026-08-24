package cloud.bamsongi.albammate.user.contract;

import java.util.Objects;

/** 사용자 계정 생성 유스케이스에 전달하는 입력이다. */
public record CreateUserAccountCommand(
	UserEmail email, RawPassword rawPassword, UserNickname nickname) {

	public CreateUserAccountCommand {
		Objects.requireNonNull(email, "email");
		Objects.requireNonNull(rawPassword, "rawPassword");
		Objects.requireNonNull(nickname, "nickname");
	}

	@Override
	public String toString() {
		return "CreateUserAccountCommand[email="
			+ email.value()
			+ ", rawPassword=<redacted>, nickname="
			+ nickname.value()
			+ "]";
	}
}
