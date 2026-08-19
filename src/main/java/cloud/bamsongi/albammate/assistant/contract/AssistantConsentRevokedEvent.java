package cloud.bamsongi.albammate.assistant.contract;

import java.time.Instant;

/** 동의 철회 뒤 AI-03이 활성 초안을 폐기할 수 있도록 전달하는 최소 사건이다. */
public record AssistantConsentRevokedEvent(long userId, Instant revokedAt) {
}
