# 측정 문서

이 문서는 `docs/measurements/`에 보존한 실측 계약, 기준선과 결과를 찾기 위한 탐색 인덱스다. 실행 절차와 해석 기준은 연결된 문서가 소유한다.

## HTTP 부하 측정

- k6 기록의 소유·보존 규칙과 소유자별 결과: [k6 측정 문서](k6/README.md)

## PostgreSQL 기준선

- 시간 기반 방 상태 보정의 처리량 기준선: [ROOM-09 일괄 처리 기준선](room-09-bounded-processing-baseline.md)
- 낙관적 락 측정의 범위와 판정 기준: [ROOM-10 측정 계약](room-10-measurement-contract.md)
- 낙관적 락 충돌 처리의 실측 기준선: [ROOM-10 동시성 기준선](room-10-optimistic-lock-baseline.md)
- PostgreSQL 후보 선점·다중 matcher 기준선의 fixture·round·결과 채택: [MATCH-01 후보 탐색 baseline 측정 계약](match-01-candidate-search-baseline-contract.md)
- MATCH 제안 응답 완료 지연의 fixture·round·경계·결과 채택: [MATCH-01 응답 완료 지연 측정 계약](match-01-response-completion-baseline-contract.md)

## 원자료

- ROOM-09·10 기준선의 보존 결과: [results](results/)

> 문서 관리: 소유자 `밤송이클럽 개발팀` · 최종 검증일 `2026-08-15` · 폐기 조건 `측정 기록이 다른 단일 인덱스로 완전히 이전될 때`
