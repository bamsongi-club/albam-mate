package cloud.bamsongi.albammate.user.contract;

/** 사용자 계정 생성 유스케이스에 전달하는 입력이다. */
public record CreateUserAccountCommand(String email, String rawPassword, String nickname) {

    @Override
    public String toString() {
        return "CreateUserAccountCommand[email="
                + email
                + ", rawPassword=<redacted>, nickname="
                + nickname
                + "]";
    }
}
