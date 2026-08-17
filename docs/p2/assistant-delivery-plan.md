# AI-01 전달 계획

> **문서 상태: 계획 초안 · 선행 결정 필요**
>
> 기준 이슈: [#794](https://github.com/bamsongi-club/albam-mate/issues/794) · 기능 ID: `AI-01`

이 문서는 AI-01을 문서·결정·계약·구현·검증 단위로 나누는 전달 계획이다. 일정이나 구현 완료를 선언하지 않으며, 승인된 결정과 실제 검증 증거가 생길 때 각 상태를 갱신한다.

## 전달 순서

### 0단계 — 범위와 문서 정본

- 기준: [#794](https://github.com/bamsongi-club/albam-mate/issues/794)
- 산출물: `AI-01` 기능 ID, [AI 모임 도우미 명세](assistant.md), 결정 초안, 이 전달 계획, 검증 설계, `DISCOVERY-01` 경계 문구, `OPS-04` 연결.
- 게이트: AI-01과 DISCOVERY-01의 읽기·쓰기 소유 경계를 문서에서 구분하고, 공개 API·ERD·아키텍처·migration은 아직 변경하지 않는다.

### 1단계 — 선행 결정

| 이슈 | 결정 영역 | 후속 산출물 | 관계 |
| --- | --- | --- | --- |
| [#795](https://github.com/bamsongi-club/albam-mate/issues/795) | AI-D01 외부 처리·동의·provider·호출·비용 경계 | `docs/adr/platform/` ADR | #794 승인 후 #796과 병렬 |
| [#796](https://github.com/bamsongi-club/albam-mate/issues/796) | AI-D02 초안·확인형 Room 생성, AI-D03 Room 지역 | `docs/adr/room/` ADR 2건 | #794 승인 후 #795와 병렬 |

두 이슈의 승인 전에는 AI-01 구현 슬라이스를 시작하지 않는다. 결정이 API·ERD·아키텍처·migration을 요구하면 해당 정본을 먼저 갱신하고 구현 이슈의 선행 링크로 고정한다.

### 2단계 — 계약과 구현 이슈 분할

| 슬라이스 | 책임 | 구현 전제 |
| --- | --- | --- |
| `AI-01a` | 동의·철회, 조건 추출, 서버 추천·추가 질문 | AI-D01, API/아키텍처 계약 |
| `AI-01b` | provider adapter, fake provider, quota·timeout·비용·fail-closed | AI-D01 ADR, 설정·관측 계약 |
| `AI-01c` | 임시 초안, 장소 입력, 지역 검증, 확인형 Room command와 멱등성 | AI-D02·D03 ADR, ERD/API/Room 계약 |
| `AI-01d` | `#/assistant` 화면, 확인 카드, 실패 상태, 수동 Room 회귀 | `AI-01a`·`AI-01c` 공개 응답 계약 |

각 구현 이슈는 소유 파일, 공개 계약, migration, 테스트 ID, rollback과 제외 범위를 선언한다. 같은 공유 파일을 여러 슬라이스가 수정하면 먼저 소유자를 정하고 계약 PR을 선행한다.

### 3단계 — 통합과 검증

1. 결정적 fake provider로 단위·계약·권한·개인정보 경계를 확인한다.
2. 확인 전 무부수효과, 확인 후 단일 Room·ChatRoom 원자성, 동일 key·동시 요청 수렴을 검증한다.
3. 기존 수동 Room 생성·참가·채팅·CSRF 회귀를 확인한다.
4. [AI-01 검증 설계](assistant-load-test.md)의 고정 fixture와 결과 판정기를 실행한다.
5. 고정 release에서 배포 검증과 `OPS-04` 사용량·비용 관측을 별도 증거로 남긴다.

## 공통 게이트

- 결정·ADR 승인 전 provider dependency·실제 provider 호출·공개 API·ERD·migration·생산 코드를 추가하지 않는다.
- AI 모델은 게임 후보·Room 쓰기 권한을 갖지 않고, 서버의 인증·인가·CSRF·도메인 command 경계를 우회하지 않는다.
- raw prompt·응답·Tool 인자·게임 후보·사용자 ID·세션·대화 원문을 앱 저장소·metric·central log에 남기지 않는다.
- 실제 provider·AWS 비용·운영 용량을 로컬 fake provider나 문서 존재만으로 완료 처리하지 않는다.
- 부하 실행의 setup 실패·관측 누락·generator 포화는 기능 실패와 섞지 않고 `INVALID`로 기록한다.

## 상태 기록

문서·결정·계약·생산 코드·자동 검증·배포·실측을 별도 축으로 [P2 기능 상태](README.md#기능별-현재-상태)에 기록한다. 이 계획의 단계가 존재하거나 이슈가 열려 있다는 사실만으로 다음 단계가 완료된 것으로 표시하지 않는다.
