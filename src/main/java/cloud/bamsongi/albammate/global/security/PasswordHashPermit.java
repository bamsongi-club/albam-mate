package cloud.bamsongi.albammate.global.security;

/** 비밀번호 해시 작업 슬롯을 반환하기 위한 토큰이다. */
public interface PasswordHashPermit extends AutoCloseable {

    @Override
    void close();
}
