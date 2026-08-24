package cloud.bamsongi.albammate.game.repository;

/** 공개 메커니즘 선택지 응답 조립에 필요한 필드만 담는 내부 값 객체다. */
public record GameMechanismOptionRow(
	String code,
	String nameKo,
	String nameEn,
	Integer featuredOrder,
	String descriptionKo) {
}
