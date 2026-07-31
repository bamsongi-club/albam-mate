# ADR-0001: Java 21과 Spring Boot 4를 백엔드 기준선으로 채택

- 상태: 승인됨
- 작성일: 2026-07-23
- 결정일: 2026-07-23
- 관련: [build.gradle](../../../build.gradle), [Gradle wrapper](../../../gradle/wrapper/gradle-wrapper.properties), [프로젝트 명령](../../COMMANDS.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

Albam Mate는 새로 시작하는 백엔드 프로젝트이며 현재 빌드는 Java toolchain 21, Spring Boot 4.1.0, Gradle 9.5.1을 사용한다. Java 17과 Spring Boot 3 계열도 현재 P0 기능을 구현할 수 있으므로, 단순히 "최신 버전"이라는 이유만으로 현재 구성을 정당화할 수는 없다.

이번 결정의 기준은 다음과 같다.

- 현재 프로젝트가 실제로 빌드 가능한 조합일 것
- 새 프로젝트에서 가까운 시기에 런타임과 프레임워크 기준선을 다시 올리는 비용을 줄일 것
- 앞으로 추가할 Spring 생태계 라이브러리와 관리형 의존성을 일관된 세대로 맞출 것
- 현재 요구에 없는 기능이나 검증하지 않은 성능 이점을 선택 근거로 과장하지 않을 것

Java 21은 장기 지원(LTS) 릴리스이고, Spring Boot 4.1.0은 Java 17부터 26까지와 Gradle 8.14 이상 또는 9.x를 공식 지원한다. 따라서 현재 Java 21·Gradle 9.5.1 조합은 Spring Boot 4.1.0의 공식 범위 안에 있다. 다만 현재 P0에는 가상 스레드 등 Java 21 전용 기능의 명확한 사용처가 없다. Java 21의 주된 선택 이유는 특정 기능이 아니라 새 프로젝트의 기준선을 이미 안정화된 세대로 두어 후속 마이그레이션을 줄이는 데 있다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| Java 21 + Spring Boot 4.1.x | 현재 빌드와 일치하고, Spring Framework 7 및 Boot 4 관리형 의존성 세대를 처음부터 사용할 수 있다. Java 21은 LTS이며 Boot 4.1의 공식 지원 범위에 포함된다. | 외부 라이브러리를 추가할 때 Spring Boot 4.1 지원 여부를 확인하고 테스트로 검증해야 한다. Boot 4.1이 비교적 새 계열이므로 패치 업데이트와 회귀 검증이 필요하다. | 선택 |
| Java 17 + Spring Boot 3.x | 더 낮은 Java 실행 기준을 유지하고 Boot 4 전환 검증을 뒤로 미룰 수 있다. | 이미 구성한 기준선을 낮춘 뒤 향후 Java 21·Boot 4로 다시 올리는 이중 변경 비용이 생긴다. 현재 저장소에서 이 조합이 더 유리하다는 구체적 요구가 확인되지 않았다. | 제외 |
| Java 21 + Spring Boot 3.x | Java 기준선은 유지하면서 Boot 3 생태계를 사용할 수 있어 프레임워크 전환을 늦출 수 있다. | Boot 4 전환을 별도 과제로 남기며, 새 프로젝트에서 서로 다른 세대의 전환을 두 번 검증하게 된다. | 제외 |
| Node.js 또는 FastAPI로 코어 백엔드 구성 | 팀 논의에서는 빠른 실험이나 별도 AI 기능 서비스의 후보로 제시됐다. | 해당 장점은 Albam Mate의 팀 숙련도·부하·운영 비용으로 검증되지 않았다. 현재 Java/Spring 빌드와 도메인·데이터 계층의 기준을 다시 세워야 하며, P0에서 전환 비용을 상쇄할 측정 결과가 없다. | 제외 |

## 결정

Albam Mate의 백엔드 기준선으로 Java 21과 Spring Boot 4.1.x를 채택하며, 최초 구현 버전은 현재 빌드에 선언된 Spring Boot 4.1.0으로 한다.

Java 21은 "최신 LTS"이거나 가상 스레드를 즉시 사용하기 때문에 선택하는 것이 아니다. Java 17과 21이 모두 현재 요구를 충족하는 상황에서, 이미 안정화된 Java 21을 새 프로젝트의 출발점으로 삼아 가까운 시기의 기준선 재변경과 마이그레이션을 줄이기 위해 선택한다. Spring Boot는 Java 21을 공식 지원하는 4.1 계열을 사용해 Spring Framework 7과 관리형 의존성의 세대를 일관되게 맞춘다.

Spring Security 7이나 향후 Spring AI 도입 가능성은 이 결정을 보조하는 호환성 고려사항일 뿐, 현재 선택의 독립적인 근거로 삼지 않는다. 해당 기술이 실제 요구에 포함되면 제품 요구와 대안을 별도로 검토한다.

## 결과

- 얻는 것: Java와 Spring 생태계의 기준 세대를 한 번에 정렬하고, 신규 프로젝트에서 Boot 3에서 4로 옮기는 별도 마이그레이션을 피한다.
- 감수할 비용·위험: 외부 라이브러리를 추가할 때 Spring Boot 4.1 지원 여부를 확인하고 테스트로 검증해야 한다. Boot 4.1.x 패치 업데이트마다 테스트와 회귀 확인이 필요하다.
- 후속 작업: CI에서 Java 21로 `test`와 `build`를 실행하고, 새 외부 라이브러리를 추가할 때 Boot 4.1 호환 여부를 PR에 기록한다. Spring Security와 인증 정책은 별도 결정으로 다룬다.

## 보류 및 재검토

- 지금 하지 않는 것:
  - 현재 P0에서 가상 스레드를 기본 활성화하거나
  - Spring AI를 미리 추가하거나
  - Java 25로 기준선을 올리는 일
- 보류 이유: 확인된 사용 지점과 부하 요구가 없고, Java 25는 이번 팀 논의에서 호환성·운영 환경을 비교하지 않았다.
- 다시 검토할 조건:
  - Java 21 지원 정책이 프로젝트 운영 기간과 맞지 않게 되거나
  - 필수 라이브러리가 다른 Java·Spring 기준을 요구하거나
  - Java 21 이상의 기능이 측정 가능한 병목을 해결하거나
  - 배포·CI 환경의 기준 JDK를 변경할 때

## 참고 자료

- [Spring Boot 4.1 시스템 요구사항](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Boot 4.1.0 릴리스](https://github.com/spring-projects/spring-boot/releases/tag/v4.1.0)
- [Spring Boot 4.0 마이그레이션 가이드](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Oracle Java SE 지원 로드맵](https://www.oracle.com/java/technologies/java-se-support-roadmap.html)

## 검증

- 상태: 검증됨
- 근거:
    - 구현:
        - `build.gradle`은 Java 21과 Spring Boot 4.1.0을 선언하고 Gradle 9.5.1 wrapper를 사용하며, 이 조합은 Spring Boot 4.1.0의 공식 지원 범위에 있다.
    - 테스트:
        - 2026-07-31 `develop`의 `6df7b3d`에서 `.\gradlew.bat conventionCheck build --no-daemon --console plain`이 전체 H2 테스트와 분기 커버리지 게이트를 포함해 통과했다.
    - CI:
        - 같은 커밋의 [CI 실행 #30597642216](https://github.com/bamsongi-club/albam-mate/actions/runs/30597642216)에서 Java 설정, `Build and verify`, `Verify PostgreSQL schema and branch coverage` 단계가 통과했다.

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
