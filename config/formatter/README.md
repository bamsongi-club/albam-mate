# Java 포맷 설정

`naver-eclipse-formatter.xml`과 `naver.importorder`는 네이버 캠퍼스 핵데이
Java 코딩 컨벤션의 아래 커밋에 포함된 설정을 사용한다.

- 원본 저장소: <https://github.com/naver/hackday-conventions-java>
- 기준 커밋: `1f277f707e2e4aa55f989d7e26420b6c7224ee84`
- Formatter 프로필: `Naver coding convention` 1.2

Spotless는 Eclipse JDT Formatter에 XML 프로필을 전달하고 import 순서는 별도
설정 파일로 적용한다. 저장소의 Java 포맷 정본은 두 파일과 `build.gradle`의
Spotless 구성이다.
