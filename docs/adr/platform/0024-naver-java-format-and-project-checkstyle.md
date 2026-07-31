# ADR-0024: 네이버 Java 포맷과 프로젝트 Checkstyle로 컨벤션을 자동화

- 상태: 승인됨
- 작성일: 2026-07-29
- 결정일: 2026-07-31
- 관련: [GitHub Issue #145](https://github.com/bamsongi-club/albam-mate/issues/145), [코드 컨벤션](../../CONVENTIONS.md), [Java 컨벤션과 Git hook 설정](../../guides/CODE_FORMATTING.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

Java 코드는 사람이 합의한 규칙을 기억하는 데 의존하지 않고, 로컬과 CI에서 같은 도구로 재현 가능한 형식이어야 한다. 기존 Google Java Format AOSP는 기본 Google 스타일의 2칸 들여쓰기를 프로젝트가 사용하던 4칸에 맞출 수 있다는 장점이 있었지만, 메서드 체이닝에서 `.`으로 시작하는 연속 줄이 8칸 들여쓰기되어 호출 흐름을 읽기 불편했다. 기존 코드에서 익숙하게 사용하던 형식과도 차이가 있었다.

프로젝트가 원하는 기준은 다음과 같다.

- 블록 들여쓰기 폭은 4로 유지한다.
- 메서드 체이닝과 긴 선언을 과도하게 안쪽으로 밀거나 자주 줄바꿈하지 않는다.
- 한 줄 기준은 120자로 두고, 포맷 결과를 모든 개발 환경에서 재현한다.
- 이름·선언 순서처럼 포맷터가 고치지 못하는 핵심 규칙도 자동으로 검사한다.
- 한글 테스트 메서드명처럼 프로젝트에 유용한 관례는 허용한다.

포맷 정본 교체는 저장소의 Java 파일 대부분을 한 번에 바꾸며, 되돌릴 때에도 같은 규모의 변경이 필요하므로 근거와 결과를 ADR로 남긴다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| Google Java Format AOSP 유지 | 이미 Spotless에 연결되어 있고 결과가 결정적이다. 4칸 블록 들여쓰기를 제공한다. | 프로젝트가 익숙한 형식과 다르고, `.`으로 시작하는 연속 줄이 8칸 들여쓰기되어 메서드 체이닝을 읽기 불편하다. 프로젝트별 세부 조정이 어렵다. | 제외 |
| 네이버 Eclipse Formatter와 프로젝트 전용 Checkstyle 조합 | 탭 폭 4와 120자 기준을 재현하며, 포맷과 필요한 정적 규칙을 각각 자동화할 수 있다. | 탭 표시 폭을 개발 환경에서 맞춰야 한다. 업스트림 설정을 갱신할 때 프로젝트 변경분과 호환성을 다시 확인해야 한다. | 선택 |
| `AGENTS.md`와 문서로만 규칙 안내 | 도구 설정이 단순하고 상황별 유연성이 크다. | 사람과 에이전트가 규칙을 누락할 수 있고, hook과 CI에서 위반을 일관되게 차단할 수 없다. | 제외 |

## 결정

Spotless의 Java 포맷 정본을 네이버 Java 코딩 컨벤션의 Eclipse Formatter 프로필과 import 순서로 교체한다. 설정은 검토한 업스트림 커밋에 고정하며, 프로젝트 기준은 탭 폭 4와 한 줄 120자로 둔다.

네이버의 모든 Checkstyle 규칙을 가져오지 않는다. 이름, 선언 순서, 와일드카드 import, 최상위 타입 수, 제어문 중괄호처럼 프로젝트에 필요한 작은 규칙 집합만 프로젝트 소유 `checkstyle.xml`로 관리한다. 한글 테스트 이름을 허용하기 위해 `*Test.java`와 `*Tests.java`에는 메서드명 규칙만 최소 범위로 제외한다.

포맷과 Checkstyle을 `conventionCheck` 하나로 묶어 pre-commit hook에서 실행한다. Gradle `check`와 `build`에도 같은 개별 검사가 포함된다. 저장소 전체를 바꾸는 최초 포맷은 설정·문서 커밋과 분리하고 `.git-blame-ignore-revs`에 등록한다.

## 결과

- 얻는 것: 메서드 체이닝을 포함해 프로젝트가 읽기 편하다고 합의한 포맷을 자동 재현하고, 핵심 네이밍·구조 규칙의 누락을 commit 전에 발견한다.
- 감수할 비용·위험: Java 파일 전체에 한 번의 대규모 포맷 변경이 생긴다. 탭 폭을 4로 표시하도록 편집기 설정을 맞춰야 하며, 포맷터 설정을 갱신할 때 결과 diff를 다시 검토해야 한다.
- 후속 작업: Issue #145에서 결정을 승인한 뒤 포맷 전용 커밋과 설정 커밋을 반영하고, 각 clone에서 포맷 커밋을 blame 제외 목록으로 사용하도록 안내한다.

## 보류 및 재검토

- 지금 하지 않는 것: 네이버 Checkstyle 전체 도입과 테스트 통합 테스트(`*IT.java`)·픽스처까지의 메서드명 예외 확대
- 보류 이유: 모든 네이버 규칙이 프로젝트 관례와 맞지는 않으며, 현재 소스에서는 더 넓은 예외가 필요하지 않다.
- 다시 검토할 조건: 현재 규칙으로 반복해서 잡지 못하는 리뷰 문제가 생기거나, 새 테스트 파일 이름 관례 때문에 타당한 메서드명이 차단될 때 최소 범위로 검토한다.

## 참고 자료

- [Naver Java Coding Conventions](https://github.com/naver/hackday-conventions-java)
- [Spotless Gradle Plugin](https://github.com/diffplug/spotless/tree/main/plugin-gradle)
- [Checkstyle](https://checkstyle.org/)

## 검증

- 상태: 검증됨
- 근거:
    - 구현:
        - [PR #152](https://github.com/bamsongi-club/albam-mate/pull/152)가 네이버 Eclipse Formatter·import 순서, 프로젝트 Checkstyle 10개 규칙, `conventionCheck`, pre-commit hook과 blame 제외 설정을 `develop`에 반영했다.
    - 테스트:
        - 2026-07-31 `develop`의 `6df7b3d`에서 `.\gradlew.bat conventionCheck build --no-daemon --console plain`이 Spotless와 main·test·postgresTest Checkstyle을 포함해 통과했다.
    - CI:
        - 같은 커밋의 [CI 실행 #30597642216](https://github.com/bamsongi-club/albam-mate/actions/runs/30597642216)에서 `Build and verify` 단계가 통과했다.

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
