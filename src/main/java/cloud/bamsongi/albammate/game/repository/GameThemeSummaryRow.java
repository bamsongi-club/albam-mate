package cloud.bamsongi.albammate.game.repository;

/** 게임 상세의 테마 응답 조립에 필요한 필드만 담는 내부 값 객체다. */
public record GameThemeSummaryRow(String code, String nameKo, String nameEn) {
}
