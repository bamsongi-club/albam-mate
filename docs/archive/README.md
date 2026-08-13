# 문서 아카이브

## P0 1차 MVP (v0.1.0)

이 디렉터리는 P0 1차 MVP 완료 시점의 문서 스냅샷이다. 원본 경로와 내용은 Git 태그 [`v0.1.0`](https://github.com/bamsongi-club/albam-mate/tree/v0.1.0)으로 고정한다.

- [P0 문서 묶음](p0/README.md)

P0 문서는 수정하지 않는다. API와 ERD는 현재 계약의 정본으로 계속 `docs/`에 유지한다. 다음 단계의 범위와 구현 기준은 [P1 명세](../P1-spec.md)와 [P1 기능 문서](../p1/README.md)에서 관리하고, 완료된 단계만 이 아카이브에 같은 구조로 보관한다.

## P1 2차 MVP 아카이브 준비

현재 P1 문서는 아직 `docs/p1/`에서 현재 정본으로 관리한다. [채팅 문서 상태 정합화 이슈 #690](https://github.com/bamsongi-club/albam-mate/issues/690)은 `CHAT-01`~`CHAT-05`의 계약·구현·자동 검증 상태와 채팅 ADR 검증 상태를 정리하며, ADR-0045의 #281 미구현·미검증 범위도 남긴다. 이 이슈는 문서 이동이나 release tag 생성을 수행하지 않는다. P1 완료 결정과 release tag를 확정한 뒤에만 P1 문서 묶음을 `docs/archive/p1/`로 스냅샷하고 `docs/p1/`의 현재 정본 책임을 전환한다.
