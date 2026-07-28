package cloud.bamsongi.albammate.auth.dto;

import cloud.bamsongi.albammate.auth.exception.ProfileValidationException;
import cloud.bamsongi.albammate.auth.validation.ValidNickname;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import jakarta.validation.constraints.NotNull;

/** 프로필 수정 요청 원문과 정규화된 내부 입력을 분리한다. */
public record ProfileUpdateRequest(@NotNull @ValidNickname String nickname) {

    public Normalized normalizeAndValidate() {
        return UserNickname.from(nickname)
                .map(UserNickname::value)
                .map(Normalized::new)
                .orElseThrow(ProfileValidationException::new);
    }

    public record Normalized(String nickname) {}
}
