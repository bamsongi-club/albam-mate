# GAME k6 부하테스트

`/api/games`의 키워드 검색과 혼합 조회 흐름을 측정하는 k6 시나리오다. 현재는 #551의 인덱스 판단에 실제 사용했거나 후속으로 사용할 `02`와 `08`만 추적한다.

## 제공 시나리오

| 파일 | 흐름 | 용도 |
| --- | --- | --- |
| `02-game-keyword.js` | 게임명 일부를 이용한 공개 키워드 검색 | 인덱스 전후 비교와 검색 기준선 |
| `08-game-realistic.js` | 목록·키워드·복합 필터·관계형 필터·예정 모임·개인화·상세 조회 혼합 | 혼합 피크와 후속 용량 측정 |
| `run-index-comparison.sh` | 같은 조건으로 두 시나리오를 실행하고 DB 상태 태그를 남김 | 임시 인덱스 비교 보조 실행기 |

`08-game-realistic.js`는 최신 release 배포를 우선해 2026-08-11 전체 카탈로그 측정에서는 실행하지 않았다. 이 스크립트가 있다고 해서 혼합 흐름의 현재 용량이 측정된 것은 아니다.

## 전제와 환경 변수

- k6가 설치되어 있어야 한다.
- 대상 DB에는 GAME 데이터가 적재되어 있어야 한다. `08`은 선택지 API(`/api/game-categories`, `/api/game-themes`, `/api/game-mechanisms`)도 조회한다.
- `BASE_URL`: 대상 서버 주소. 기본값은 `http://localhost:8080`이다.
- `SESSION_COOKIE`: `JSESSIONID=...` 형태의 전체 세션 쿠키다.
- `JSESSIONID`: 값만 전달하면 `SESSION_COOKIE`로 변환한다.
- `PROFILE`: `08`의 부하 프로파일(`load`, `spike`, `stress`, `soak`)이다. 기본값은 `load`다.
- `SOAK_DURATION`: `soak` 유지 시간이다. 기본값은 `1h`다.

`SESSION_COOKIE`와 `JSESSIONID`는 실제 로그인 정보이므로 커밋하지 않는다. 세션이 없으면 `08`의 개인화 5% 구간은 익명 목록 조회로 대체된다.

## 실행

저장소 루트에서 실행한다.

```bash
k6 run load-tests/k6/game/02-game-keyword.js

k6 run \
  -e PROFILE=load \
  load-tests/k6/game/08-game-realistic.js
```

로그인 세션을 포함한 혼합 흐름은 다음처럼 실행한다.

```bash
k6 run \
  -e JSESSIONID="SESSION_ID" \
  -e PROFILE=load \
  load-tests/k6/game/08-game-realistic.js
```

인덱스 비교 실행기는 DB 상태를 직접 바꾸지 않는다. disposable PostgreSQL에서 상태를 준비한 뒤, 같은 `BASE_URL`, `PROFILE`, fixture로 상태별로 한 번씩 실행한다.

```bash
INDEX_STATE=no-pg-trgm \
  bash load-tests/k6/game/run-index-comparison.sh

INDEX_STATE=pg-trgm-gin \
  bash load-tests/k6/game/run-index-comparison.sh
```

원시 summary와 로그는 기본적으로 `build/k6/game/`에 생성되며 Git에 커밋하지 않는다. 정본 결과는 [키워드 검색 용량 기록](../../../docs/measurements/k6/keyword-search-capacity-2026-08-11.md)과 [인덱스 판단 기록](../../../docs/measurements/k6/keyword-search-index-decision-2026-08-11.md)으로 보존한다.

## 검증

```bash
node --test load-tests/k6/game/tests/scenarios.test.mjs
```

이 검증은 두 시나리오의 k6 bundle 해석과 비교 실행기의 Bash 구문·필수 `INDEX_STATE` 검사를 확인한다. 실제 부하는 발생시키지 않는다.
