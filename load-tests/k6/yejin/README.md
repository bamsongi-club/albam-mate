# GAME k6 부하테스트

이 디렉터리는 Yejin이 소유하며, `/api/games`의 키워드 검색과 혼합 조회 흐름을 측정하는 k6 시나리오를 관리한다. 소스와 결과의 공통 배치 규칙은 [Load Tests](../../README.md)를 따른다. 현재는 #551의 인덱스 판단에 실제 사용했거나 후속으로 사용할 `02`와 `08`만 추적한다.

## 제공 시나리오

| 파일 | 흐름 | 용도 |
| --- | --- | --- |
| `00-game-keyword-contract.js` | 고정 검색어 응답 구조·전체 건수 1회 검증 | 잘못된 API·fixture 상태에서 비교 실행 차단 |
| `02-game-keyword.js` | 게임명 일부를 이용한 공개 키워드 검색 | 인덱스 전후 비교와 검색 기준선 |
| `08-game-realistic.js` | 목록·키워드·복합 필터·관계형 필터·예정 모임·개인화·상세 조회 혼합 | 혼합 피크와 후속 용량 측정 |
| `run-index-comparison.sh` | 같은 조건으로 두 시나리오를 실행하고 DB 상태 태그를 남김 | 임시 인덱스 비교 보조 실행기 |

`08-game-realistic.js`의 `load` profile은 2026-08-11 전체 카탈로그 인덱스 비교에서 실행했다. `spike`, `stress`, `soak` profile과 로그인 개인화 흐름의 용량은 아직 측정하지 않았다.

## 전제와 환경 변수

- k6가 설치되어 있어야 한다.
- 대상 DB에는 GAME 데이터가 적재되어 있어야 한다. `08`은 선택지 API(`/api/game-categories`, `/api/game-themes`, `/api/game-mechanisms`)도 조회한다.
- `BASE_URL`: 대상 서버 주소. 기본값은 `http://localhost:8080`이다.
- `SESSION_COOKIE`: `JSESSIONID=...` 형태의 전체 세션 쿠키다.
- `JSESSIONID`: 값만 전달하면 `SESSION_COOKIE`로 변환한다.
- `PROFILE`: `08`의 부하 프로파일(`load`, `spike`, `stress`, `soak`)이다. 기본값은 `load`다.
- `SOAK_DURATION`: `soak` 유지 시간이다. 기본값은 `1h`다.
- `KEYWORD`: 인덱스 비교에서 모든 VU가 사용할 고정 검색어다.
- `EXPECTED_TOTAL_ELEMENTS`: 고정 검색어와 일치해야 하는 fixture의 전체 게임 수다.
- `BENCHMARK_ID`: 인덱스 OFF·ON 실행을 한 쌍으로 묶는 식별자다.
- `RELEASE_SHA`: 두 실행이 공유할 40자리 애플리케이션 Git SHA다.
- `FIXTURE_ID`: fixture 버전이나 dump 이름을 나타내는 안정적인 식별자다.
- `FIXTURE_SHA256`: 두 실행이 공유할 fixture 파일의 SHA-256이다.

`SESSION_COOKIE`와 `JSESSIONID`는 실제 로그인 정보이므로 커밋하지 않는다. 세션이 없으면 `08`의 개인화 5% 구간은 익명 목록 조회로 대체된다.

## 실행

저장소 루트에서 실행한다.

```bash
k6 run load-tests/k6/yejin/02-game-keyword.js

k6 run \
  -e PROFILE=load \
  load-tests/k6/yejin/08-game-realistic.js
```

로그인 세션을 포함한 혼합 흐름은 다음처럼 실행한다.

```bash
k6 run \
  -e JSESSIONID="SESSION_ID" \
  -e PROFILE=load \
  load-tests/k6/yejin/08-game-realistic.js
```

인덱스 비교 실행기는 DB 상태를 직접 바꾸지 않는다. disposable PostgreSQL에서 상태를 준비한 뒤, 같은 `BASE_URL`, `PROFILE`, fixture로 상태별로 한 번씩 실행한다. 아래 여섯 필수값과 스크립트 checksum, k6 버전, 인증 여부가 기존 manifest와 다르면 두 번째 실행을 거부한다.

```bash
BENCHMARK_ID=game-551-20260811 \
KEYWORD=누스피요르드 \
EXPECTED_TOTAL_ELEMENTS=1 \
RELEASE_SHA=6a19713e053235b28fc075f1c1e7ad6351fda538 \
FIXTURE_ID=catalog-170k-v1 \
FIXTURE_SHA256=<fixture-sha256> \
INDEX_STATE=no-pg-trgm \
  bash load-tests/k6/yejin/run-index-comparison.sh

BENCHMARK_ID=game-551-20260811 \
KEYWORD=누스피요르드 \
EXPECTED_TOTAL_ELEMENTS=1 \
RELEASE_SHA=6a19713e053235b28fc075f1c1e7ad6351fda538 \
FIXTURE_ID=catalog-170k-v1 \
FIXTURE_SHA256=<fixture-sha256> \
INDEX_STATE=pg-trgm-gin \
  bash load-tests/k6/yejin/run-index-comparison.sh
```

runner는 상태별 부하를 시작하기 전에 고정 검색어 응답의 `data.content` 구조와 `totalElements`를 한 번 검증한다. 같은 benchmark에 이미 기록된 `INDEX_STATE`는 기존 산출물을 덮어쓰지 않고 거부한다. 실행별 summary JSON과 stdout·stderr 로그, 기대 건수·preflight checksum을 포함한 불변 조건과 산출물 SHA-256을 묶은 version 2 manifest는 기본적으로 `build/k6/game/`에 생성되며 Git에 커밋하지 않는다. threshold 위반으로 k6가 실패해도 두 부하 시나리오의 결과와 manifest를 먼저 보존한 뒤 실패 코드를 반환한다. 정본 결과는 [키워드 검색 용량 기록](../../../docs/measurements/k6/yejin/keyword-search-capacity-2026-08-11.md), [인덱스 판단 기록](../../../docs/measurements/k6/yejin/keyword-search-index-decision-2026-08-11.md), [검증 증거 JSON](../../../docs/measurements/k6/yejin/evidence/keyword-search-index-comparison-2026-08-11.json)으로 보존한다.

## 검증

```bash
node --test load-tests/k6/yejin/tests/scenarios.test.mjs
```

이 검증은 세 시나리오의 k6 bundle 해석, 비-200·검색 결과 계약·메타데이터 setup 실패 전파, 고정 검색어, 비교 실행기의 중복 방지·필수 provenance·manifest·로그 계약을 로컬 fake API로 확인한다. 운영 환경에는 부하를 발생시키지 않는다.
