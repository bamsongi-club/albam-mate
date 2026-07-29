package cloud.bamsongi.albammate.global.security.ratelimit;

/** 동일한 정규화 이메일·원격 IP 조합의 검증을 하나만 진행하도록 예약하는 토큰이다. */
public interface LoginVerificationPermit extends AutoCloseable {

    @Override
    void close();
}
