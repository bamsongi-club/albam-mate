package cloud.bamsongi.albammate.auth.service;

/** 로그인 유스케이스에 전달하는 정규화된 내부 입력이다. */
public record LoginCommand(String email, String password) {

    @Override
    public String toString() {
        return "LoginCommand[email=" + email + ", password=<redacted>]";
    }
}
