package cloud.bamsongi.albammate.auth.dto;

import cloud.bamsongi.albammate.auth.exception.ProfileValidationException;
import cloud.bamsongi.albammate.auth.validation.ValidProfileUpdateRequest;

/** 프로필 수정 요청 원문과 정규화된 내부 입력을 분리한다. */
@ValidProfileUpdateRequest
public record ProfileUpdateRequest(String nickname) {

    public Normalized normalizeAndValidate() {
        if (nickname == null) {
            throw new ProfileValidationException();
        }

        String normalizedNickname = nickname.strip();
        int codePointCount = normalizedNickname.codePointCount(0, normalizedNickname.length());
        if (codePointCount < 1
                || codePointCount > 50
                || normalizedNickname.codePoints().anyMatch(Character::isISOControl)) {
            throw new ProfileValidationException();
        }
        return new Normalized(normalizedNickname);
    }

    public record Normalized(String nickname) {}
}
