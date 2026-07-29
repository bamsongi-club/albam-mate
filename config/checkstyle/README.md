# Albam Mate Checkstyle

이 설정은 네이버 캠퍼스 핵데이 Java 코딩 컨벤션의 기계적으로 판정 가능한
규칙을 참고하되 Albam Mate의 현재 규칙과 Java 21 코드에 맞게 최소 구성한
프로젝트 정본이다.

- 참고: <https://github.com/naver/hackday-conventions-java>
- Checkstyle: `13.8.0`

## 적용 규칙

1. `PackageName`
2. `TypeName`
3. `MethodName`
4. `MemberName`
5. `ParameterName`
6. `LocalVariableName`
7. `AvoidStarImport`
8. `ModifierOrder`
9. `OneTopLevelClass`
10. `NeedBraces`

들여쓰기, 공백, 줄바꿈과 import 순서는 Spotless에 맡기므로 Checkstyle에서
중복 검사하지 않는다. `*Test.java`와 `*Tests.java`는 행동을 설명하는 한글 테스트
메서드명을 허용하기 위해 `MethodName` 검사에서 제외한다. 나머지 규칙의 예외는
근거를 이 문서에 추가한 뒤 `suppressions.xml`에 최소 범위로 선언한다.
