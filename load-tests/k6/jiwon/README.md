# Jiwon k6 시나리오

이 폴더는 Jiwon이 소유하는 k6 시나리오의 진입점이다. 현재는 [ROOM 핵심 HTTP k6 테스트](room/README.md) 5종을 제공한다.

- 실행 source, fixture 생성기와 계약 테스트는 이 폴더 아래에 추적한다.
- 실제 fixture, 비밀번호·세션·CSRF, k6 원시 결과는 `build/k6/room/<run-id>/`에만 둔다.
- 아직 승인해 보존한 ROOM k6 측정 문서는 없다. 실행 결과를 정본 문서로 승격할 때만 `docs/measurements/k6/jiwon/` 아래에 추가한다.
