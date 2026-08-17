# AI-01 결정 초안

> **문서 상태: 승인 전 초안**
>
> 기준 이슈: [#794](https://github.com/bamsongi-club/albam-mate/issues/794) · 후속 결정: [#795](https://github.com/bamsongi-club/albam-mate/issues/795), [#796](https://github.com/bamsongi-club/albam-mate/issues/796)

이 문서는 `AI-01`의 범위와 후속 결정의 관계를 저장소에서 검토할 수 있도록 정리한 초안이다. 아래 우선안은 승인 전 제안이며 API·ERD·아키텍처·migration·생산 코드의 확정 계약이 아니다.

## 결정 흐름

~~~text
#794 AI-01 활성화·소유 경계
  ├─ #795 AI-D01 외부 AI 처리·동의·provider 운영 경계
  └─ #796 AI-D02 초안·확인형 Room 생성 + AI-D03 Room 지역
~~~

- #794가 `AI-01` 기능 ID, 범위, `DISCOVERY-01`과의 경계를 먼저 고정한다.
- #795와 #796은 #794 이후 병렬 검토할 수 있지만, 두 결정과 ADR이 승인되기 전에는 AI-01 구현 슬라이스를 시작하지 않는다.
- `OPS-04`는 AI-D01이 확정한 provider·model·호출·가격 snapshot 경계를 관측 계약으로 참조한다. `OPS-04`가 AI 기능 정책을 대신 결정하지 않는다.

## AI-01과 `DISCOVERY-01`의 경계

| 구분 | `AI-01` AI 모임 도우미 | `DISCOVERY-01` 게임 탐색 도우미 |
| --- | --- | --- |
| 주 사용자 흐름 | 자연어 조건 → 서버 추천 → 명시적 확인 → Room 생성 | 자연어 게임 탐색 → `SEARCH-04` 읽기 검색 → 근거 있는 결과 |
| 상태 변경 | 확인 뒤 기존 Room 생성 command 호출 | 없음. Room·참가·채팅·매칭 쓰기 tool 제외 |
| 화면 | 별도 `#/assistant` 제안 | 공개 게임 탐색 화면 |
| 후보 근거 | 서버가 승인한 조건·후보 조회 경계 | `SEARCH-04` 의미 검색 결과 |
| 소유하지 않는 것 | provider·저장·공개 계약은 승인 ADR/API/ERD 소유 | provider·model·저장·공개 계약은 승인 ADR/API/ERD 소유 |

두 기능이 같은 자연어 입력이나 게임 후보를 사용할 때는 호출 경계와 정본 소유자를 별도 계약으로 연결한다. 한 기능의 읽기 전용 계약을 다른 기능의 Room 쓰기 권한으로 확장하지 않는다.

`AI-01`의 후보 조회는 후속 `game.contract` AI-01 후보 조회 port가 소유하고, `DISCOVERY-01`의 `SEARCH-04` tool이나 `game` repository·catalog 직접 접근을 재사용하지 않는 방향으로 경계를 고정한다. 후보 필터·정렬·응답 필드는 API·아키텍처 승인 때 확정하며, 기존 `GameQuery` 요약 조회는 이미 확정된 game ID 보강에만 사용한다.

## AI-D01 — 외부 AI 처리·동의·provider 운영 경계

### 제안 우선안

- 외부 provider와 애플리케이션 사이에는 `assistant.contract.AssistantIntentExtractor` 하나의 port만 둔다. provider SDK는 `game`·`room` 서비스에 직접 주입하지 않는다.
- provider payload는 버전이 지정된 instruction·강제 tool schema·기준 시각·사용자 현재 문장·서버가 식별한 누락 필드의 allowlist로 제한한다.
- 모델은 `propose_game_room_intent` 구조화 결과만 만들며 게임 검색·Room 쓰기·자동 tool loop 권한을 갖지 않는다.
- 외부 처리 동의와 철회를 별도 상태로 관리하고, 철회 뒤 새 AI 호출과 활성 초안을 막는다.
- raw prompt·모델 원문 응답·대화 이력·prompt hash·BGG 텍스트·게임 후보·사용자 ID·세션을 앱 저장소와 중앙 관측에 보존하지 않는다.
- 기본 profile과 CI는 결정적 fake provider를 사용하고, 실제 provider는 승인된 수동 smoke에서만 호출한다.
- 제안된 운영 한도는 사용자 10분 5회·KST 일 30회·동시 1회·timeout 8초·retry 0이며, 실제 채택 여부는 #795에서 확정한다.
- Redis 원자 예약 기반 비용 hard cap과 알림, Redis 불능 시 fail-closed를 검토한다. 금액·오류 응답·알림 경로는 #795에서 확정한다.

### 승인 전 남은 항목

- provider·Spring AI 버전과 배포 계정에서 사용할 실제 model ID
- 동의문, provider 정책 버전·URL, 철회 효력과 보존 범위
- 호출 한도·timeout·retry·비용 cap 값과 공개 오류 코드
- `OPS-04`와 AI-D01의 metric·log·가격 snapshot 소유 관계
- BGG 승인 release·field·processing allowlist와 provider payload의 최종 교차 검증

## AI-D02 — 초안과 확인형 Room 생성

### 제안 우선안

- `RECOMMEND`는 Room 생성 전용 필드를 요구하지 않고, 검색 조건이 유효할 때 후보 집합만 반환한다. 이 액션은 활성 임시 Room 초안을 만들지 않는다.
- 사용자가 후보를 선택하고 방 생성 정보를 채워 `CREATE_ROOM`으로 전환한 뒤에만 15분 임시 초안을 만들며, 자연어 명령만으로 Room을 즉시 만들지 않는다.
- 사용자당 활성 초안은 하나이며 새 방 만들기 명령·동의 철회·만료·명시 폐기 시 기존 초안을 종결한다.
- 상세 장소는 확인 카드에서 사용자가 직접 입력하고 모델 결과나 raw prompt에서 저장하지 않는다.
- confirm은 `Idempotency-Key`와 draft version을 함께 사용한다. 이미 확인된 같은 key는 version 검사보다 먼저 같은 Room을 반환하고, 다른 key·오래된 version·만료 초안은 새 Room을 만들지 않는다.
- assistant는 `room.contract`의 확인형 command만 호출하며, 현재 사용자 ID는 command 내부에서 읽는다.
- 기존 `RoomCreated → ChatRoom` 원자 흐름을 재사용하고 Room 생성 실패 시 ChatRoom을 남기지 않는다.

### 승인 전 남은 항목

- 초안 수명과 만료 판정 시점
- confirmation key의 hash·보존 기간·재시도 허용 창
- 동시 confirm의 PostgreSQL 잠금·version·유일 제약 조합
- 만료·타인·오래된 version·다른 key의 공개 오류 계약
- API·ERD·아키텍처에서 기존 즉시 생성 경로와 확인형 경로를 공존시키는 방법

## AI-D03 — Room 지역 계약

### 제안 우선안

- 지역은 `홍대`, `강남`, `건대`, `잠실`의 닫힌 집합으로 제한한다.
- 공개 API wire value는 기존 한국어 문자열 관례를 유지하며, AI는 지역만 제안하고 상세 장소는 별도 `place` 입력으로 분리한다.
- 새 생성 요청은 지역을 명시하고, 전환 기간에 누락된 요청은 기존 호환을 위해 `홍대`로 해석하는 방안을 검토한다.
- 기존 Room 행은 재작성하지 않고 기존 `PATCH`의 지역 수정 불가 규칙을 유지한다. P2에서는 지역 목록 검색 필터를 추가하지 않는다.

### 승인 전 남은 항목

- 네 지역 목록의 최종 확정과 추가 지역의 재검토 기준
- DB 저장 표현과 `CHECK`·enum·애플리케이션 검증 중 제약 방식
- `region` 요청 필드의 optional 전환 기간·종료 조건·기존 클라이언트 호환
- 기존 운영 데이터의 허용값 확인과 migration 필요 여부
- API·ERD·아키텍처·migration 변경의 소유 이슈와 rollback 방법

## 승인 게이트

이 초안은 다음을 확인하기 전에는 `계약 준비 완료`로 승격하지 않는다.

1. 팀이 #794의 `AI-01` 범위와 `DISCOVERY-01` 경계를 승인한다.
2. #795의 AI-D01과 #796의 AI-D02·D03이 각각 사람 승인 코멘트와 정식 ADR을 갖는다.
3. 승인된 결정과 API·ERD·아키텍처의 소유 문서가 서로 모순되지 않는다.
4. 구현 이슈가 `AI-01a`~`AI-01d`의 대상 경로·테스트 계약·migration·rollback 경계를 선언한다.

승인 전에는 문서·검증 설계만 갱신하고 provider dependency, application 설정, 공개 endpoint, 테이블, Java/프런트엔드 코드를 변경하지 않는다.
