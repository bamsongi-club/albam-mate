# ADR-0066: MATCH 계약의 단일 정본 소유권 전환

- 상태: 승인됨
- 작성일: 2026-08-16
- 결정일: 2026-08-16
- 관련: [P2 문서별 단일 책임](../../P2-spec.md#문서별-단일-책임), [MATCH-01 명세](../../p2/matching.md), [API 명세](../../API.md), [ERD](../../ERD.md), [아키텍처](../../ARCHITECTURE.md), [MATCH-01 후보 탐색 baseline 측정 계약](../../measurements/match-01-candidate-search-baseline-contract.md)
- 대체 대상: [ADR-0062](0062-match-chat-handoff-recovery-retention.md)의 제품 시간·보관·신고 상세와 복구 실행 상세 소유권, [ADR-0063](0063-match-baseline-measurement-gate.md)의 baseline fixture·metric·round·결과 채택 상세 소유권, [ADR-0065](0065-match-candidate-claim-baseline-scope.md)의 candidate fixture·개수·acceptance와 증거 manifest mechanics 상세 소유권
- 후속 ADR: 없음

## 맥락

MATCH 계약을 준비하면서 기능 명세, API, ERD, 아키텍처, ADR과 측정 계약에 같은 규칙의 상세 설명이 함께 기록됐다. 이 구조에서는 한 규칙을 바꿀 때 여러 문서를 동시에 수정해야 하며, 문서마다 표현이 달라지면 어느 문장이 현재 계약인지 판정하기 어렵다. [P2 문서별 단일 책임](../../P2-spec.md#문서별-단일-책임)에 따라 규칙마다 활성 정본을 하나만 두고 나머지 문서는 그 정본을 링크해야 한다.

ADR-0062·ADR-0063·ADR-0065는 이미 승인돼 결정 본문을 사후 편집할 수 없다. 따라서 기존 본문은 결정 당시의 역사적 기록으로 보존하되, 반복된 계약 상세의 현재 소유권만 후속 ADR로 전환해야 한다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 기존 문서의 반복 상세를 모두 활성 계약으로 유지 | 과거 문장을 그대로 사용할 수 있다. | 변경 때마다 복수 문서를 동기화해야 하고 충돌 시 정본을 판정할 수 없다. | 제외 |
| 승인 ADR의 결정 본문을 직접 고쳐 반복 상세를 제거 | 현재 문서만 보면 간결하다. | 승인 당시 결정 기록을 변경해 ADR의 불변성과 감사 가능성을 훼손한다. | 제외 |
| 승인 ADR 본문은 역사적 기록으로 보존하고 후속 ADR이 활성 정본 소유권을 각 책임 문서로 전환 | 승인 기록을 보존하면서 현재 계약을 한 곳에서 판정할 수 있다. | 과거 ADR을 읽을 때 후속 ADR과 링크된 정본을 함께 확인해야 한다. | 선택 |

## 결정

1. MATCH 제품 흐름·상태·시간·보관·신고 규칙은 [MATCH-01 명세](../../p2/matching.md)가 소유한다. HTTP 요청·응답 필드, 상태 표현과 오류 의미는 [API 명세](../../API.md)가 소유한다.
2. 테이블·컬럼·제약·인덱스와 제품 규칙의 저장 투영은 [ERD](../../ERD.md)가 소유한다. 모듈 책임, 트랜잭션, 잠금, 재시도와 복구 실행 흐름은 [아키텍처](../../ARCHITECTURE.md)가 소유한다.
3. candidate claim baseline의 fixture·metric·round·결과 채택과 종합 증거 manifest mechanics는 [MATCH-01 후보 탐색 baseline 측정 계약](../../measurements/match-01-candidate-search-baseline-contract.md)이 소유한다.
4. ADR-0062·ADR-0063·ADR-0065 본문에서 위 상세를 설명한 문장은 결정 당시의 역사적 기록이며 활성 계약이 아니다. 현재 해석과 구현은 이 ADR이 연결한 단일 소유 문서를 따른다.
5. ADR-0062의 MATCH 전용 채팅 선택, matching과 chat의 도메인 분리, 원자적 port 협력 선택과 개인정보 최소화 근거는 계속 유효하다. ADR-0063의 증거 우선 PostgreSQL 선택과 baseline 전 성능 목표·Redis 업무 락을 채택하지 않는 결정도 계속 유효하다. ADR-0065의 candidate claim 측정과 최종 상태 정합성 증거를 분리하는 결정도 계속 유효하다.
6. 비소유 문서는 구현에 필요한 이름이나 저장 투영을 표시할 수 있지만 정책·산식·실행 절차·측정 판정을 다시 정의하지 않고 해당 활성 정본을 링크한다.

## 결과

- 각 계약 상세의 변경과 판정 지점이 하나로 고정된다.
- 승인 ADR은 선택 이유·대안·장단점·재검토 조건의 역사적 근거로 남고, 현재 계약의 중복 소유자가 되지 않는다.
- API와 ERD의 공개·저장 표현, 아키텍처의 실행 방식, 측정 계약의 재현 규칙을 서로 혼합하지 않는다.
- 기존 ADR 본문과 활성 정본을 함께 읽어야 하므로 후속 ADR 링크를 유지해야 한다.

## 적용·호환·rollback

- 적용: MATCH 문서의 반복 상세를 제거하거나 이름 기반 투영으로 바꾸고 이 ADR이 지정한 정본을 링크한다. 제품 동작이나 구현 범위를 새로 추가하지 않는다.
- 호환: ADR-0062·ADR-0063·ADR-0065의 고유 기술 선택과 재검토 조건은 유지한다. 기존 본문은 삭제하거나 수정하지 않는다.
- rollback: 소유권 전환이 문서 해석을 모호하게 만들면 이 ADR을 직접 편집하지 않고, 문제 범위와 새 단일 소유자를 정하는 후속 ADR로 대체한다.

## 보류 및 재검토

- 지금 하지 않는 것: 하나의 거대 문서로 모든 MATCH 계약 합치기, 승인 ADR 본문 재작성, 링크 없이 상세 복제 허용
- 다시 검토할 조건: 하나의 규칙이 두 소유 문서에서 서로 다른 활성 계약으로 판정되거나, 링크만으로 구현·검증에 필요한 경계를 찾을 수 없을 때

## 참고 자료

- [P2 문서별 단일 책임](../../P2-spec.md#문서별-단일-책임)
- [MATCH-01 명세](../../p2/matching.md)
- [MATCH-01 후보 탐색 baseline 측정 계약](../../measurements/match-01-candidate-search-baseline-contract.md)
- [ADR-0062](0062-match-chat-handoff-recovery-retention.md)
- [ADR-0063](0063-match-baseline-measurement-gate.md)
- [ADR-0065](0065-match-candidate-claim-baseline-scope.md)

## 검증

- 상태: 검증됨
- 근거:
    - 계약: MATCH 제품·API·저장·실행·측정 상세의 활성 소유 문서를 이 ADR과 각 비소유 문서의 링크로 구분하고, 승인 ADR 본문은 바꾸지 않은 채 후속 ADR 메타데이터로 역사적 기록과 활성 계약을 분리했다.
    - 테스트: `node scripts/docs/check-doc-links.mjs`, `node --test scripts/docs/check-doc-links.test.mjs`, `node scripts/docs/check-monitoring-contract.mjs`, `node --test scripts/docs/check-monitoring-contract.test.mjs`, `git diff --check`가 통과했다.

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
