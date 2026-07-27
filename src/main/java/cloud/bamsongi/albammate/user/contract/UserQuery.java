package cloud.bamsongi.albammate.user.contract;

import java.util.Optional;

/** 다른 모듈이 공개 사용자 정보를 조회할 때 사용하는 최소 계약이다. */
public interface UserQuery {

    /**
     * 사용자 ID로 공개 표시용 닉네임만 조회한다.
     *
     * @param userId 알밤메이트 내부 사용자 ID
     * @return 사용자가 없으면 {@link Optional#empty()}
     */
    Optional<String> findNicknameById(Long userId);
}
