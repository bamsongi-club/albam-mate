package cloud.bamsongi.albammate.auth.dto;

import cloud.bamsongi.albammate.auth.exception.ProfileValidationException;
import cloud.bamsongi.albammate.auth.validation.ValidProfileUpdateRequest;
import cloud.bamsongi.albammate.user.contract.UserNickname;

/** 프로필 수정 요청 원문과 정규화된 내부 입력을 분리한다. */
@ValidProfileUpdateRequest
public record ProfileUpdateRequest(String nickname) {

    public Normalized normalizeAndValidate() {
        return UserNickname.from(nickname)
                .map(UserNickname::value)
                .map(Normalized::new)
                .orElseThrow(ProfileValidationException::new);
    }

    public record Normalized(String nickname) {}
}
