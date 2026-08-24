package cloud.bamsongi.albammate.game.repository;

/** 게임 상세의 공개 메커니즘 응답 조립에 필요한 필드만 담는 내부 값 객체다. */
public record GameMechanismSummaryRow(String code, String nameKo, String nameEn) {
}
