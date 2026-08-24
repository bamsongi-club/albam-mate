# P1 게임 메커니즘 적재 품질 보고서

- 상태: 대상 검증 통과
- 승인 입력: `games.p1-search-time-corrected-2026-08-03.json` SHA-256 `c8800eaf0f4e276722162f1371d72cab08ae6ac440e730c2567321300c4a9cf6`, `boardgames_ranks07-24.csv` SHA-256 `b706d0ae3722e063f6b36b9faaf97f3533fce45605c0dfe01c347a68ea2aa56d`
- 검수 범위: 공개 메커니즘 189개, 게임-메커니즘 관계 13,263개, 관계가 있는 게임 1,998개
- 검수자·일시: `beyejin`, `2026-08-04T01:59:39Z`
- 승인 근거: Issue #351, T1~T5 승인 코멘트, ADR-0048

`prepare-game-catalog.mjs`는 정확한 checksum과 승인 manifest를 확인한 뒤 `service-mechanism-catalog.json`에 BGG 원본 ID·영문명·내부 코드·한국어명·대표 순서를, `upsert-game-mechanisms.sql`에 중복 관계를 피하는 UPSERT를 결정적으로 생성한다. `approvedCodes`와 그 SHA-256은 BGG ID별 공개 code를 고정하므로 입력 영문명이 달라져도 code를 자동으로 바꾸지 않는다. 매핑 누락이나 checksum 불일치는 산출을 차단한다. 원본 JSON·CSV와 생성 JSON·SQL은 커밋하지 않는다.

검증 명령은 2026-08-04에 동일 승인 입력으로 완료했으며, 산출물은 `ready`, 공개 목록 189개, 관계 13,263개를 보고했다. `HAND_MANAGEMENT`와 `DICE_ROLLING`도 승인 code 그대로 확인했다. 대표 순서는 핸드 관리, 주사위 굴림, 셋 컬렉션, 협력 게임, 타일 놓기, 조립 보드, 솔로/솔로테어 게임, 일꾼 놓기 순으로 확인했다.
