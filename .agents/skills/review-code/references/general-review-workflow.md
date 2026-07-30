# 일반 리뷰 실행 계약

## 완료와 coverage

- 변경의 실패 경로와 계약 근거를 위험 ID로 나누고 각 대상 hunk 또는 파일 구간에 담당 샤드 하나를 배정한다.
- 샤드는 배정된 위험 ID와 대상 범위를 모두 확인했을 때만 coverage 완료다.
- 시간·문맥·슬롯 제약으로 한 번에 검토하기 어려운 범위는 더 작은 논리 경계로 다시 나눈다. 끝내 확인하지 못한 파일·hunk·위험 ID가 있으면 최종 판정을 `Incomplete`로 한다.

## 공통 준비

- branch 또는 worktree 모드에서는 고정한 전체 스냅샷에 대해 `git status --short`, `git diff --stat`, 필요한 hunk와 `git diff --check`를 한 번 확인한다. stat은 위험과 대형 diff 판단에 쓴다.
- 저장소 정본이 요구하는 테스트·링크 검사는 오케스트레이터가 한 번만 실행하고 실제 결과를 남긴다.
- 외부 계약은 변경 이해에 필수이거나 후보의 채택·심각도 판단에 필요할 때만 공식 1차 출처로 한 번 확인한다.

## reviewer 배치

- 범위와 라우팅에서 선택한 수와 차원으로 `review-code-reviewer`를 배치한다.
- 각 reviewer에는 범위와 라우팅에서 고정한 shard 입력만 전달한다.
- 각 agent의 모델·reasoning effort·sandbox는 `.codex/agents/` 설정을 정본으로 삼는다.
- 지정 agent를 쓸 수 없으면 현재 런타임 기본값으로 폴백하고 실제 프로필을 최종 보고에 남긴다.

## 병합과 재판정

- 같은 위치·같은 문제를 하나로 합치고 coverage 장부의 미검토 범위를 먼저 처리한다.
- 추가·수정·문맥 라인은 head의 해당 줄과 문맥으로, 삭제 라인은 preimage와 `LEFT` diff 문맥으로 다시 검증한다.
- 보안·개인정보, critical·major, 차원 간 상충, medium confidence 후보만 `review-code-judge`에 한 요청으로 묶어 재판정한다.
- high-confidence minor·nit만 있으면 judge를 실행하지 않는다.
- judge에는 candidate ID, 고정 base/head SHA 또는 파일 스냅샷, 정확한 파일 구간 또는 diff hunk와 전후 문맥, 계약 요약을 전달한다.
- `low confidence` 후보는 제외한다.
- 검증된 Finding만 최종 형식으로 확장한다.
