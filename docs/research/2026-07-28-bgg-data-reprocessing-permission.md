# BGG 데이터 번역·요약·재가공 허가와 독자 조사 자료의 권리 경계

- 조사일: 2026-07-28
- 대상: 학생 주도·공개 운영 예정이며 수익화 방식이 미확정인 Albam Mate의 BGG XML API·전체 게임 CSV 이용
- 상태: 공식 공개 자료 검토 완료, 승인 메일 원문과 BGG의 추가 회신은 미검토

> 이 문서는 공개된 약관과 대한민국 법령을 정리한 일반 정보이며 법률 자문이 아니다. 실제 분쟁 가능성이나 상업화 전에는 대한민국·미국 관련 법률 전문가의 검토가 필요하다.

## 결론

1. 일반적인 BGG 애플리케이션 승인은 API·전체 게임 CSV에 접근하기 위한 승인이지, 번역·요약·정규화 같은 재가공을 허용하는 별도 서면 동의로 보기는 어렵다. BGG 표준 XML API 조건은 비상업적 범위의 복제·표시는 허용하지만 API로 얻은 데이터를 수정하지 말라고 명시한다. 승인 유형과 메일 원문을 확인하고, 이 금지의 예외가 구체적으로 적혀 있지 않다면 기존 메일 스레드에서 명시적 허가와 상업·비상업 분류를 추가로 받아야 한다.
2. 인터넷에서 확인한 **객관적 사실**을 여러 적법한 출처로 검증하고, 원문 표현을 가져오지 않은 채 팀이 새 한국어 문장과 독자적인 분류를 작성했다면 그 새 창작적 표현은 원칙적으로 작성자의 저작물이 될 수 있다. 그러나 공개된 인터넷 자료라는 이유만으로 원 설명문·이미지·번역문·대규모 데이터 집합까지 자유롭게 가져올 수 있는 것은 아니다.
3. 개별 사실 자체와 사실을 모은 데이터베이스는 구분해야 한다. 대한민국 저작권법상 데이터베이스의 소재 자체에는 데이터베이스제작자 권리가 미치지 않지만, 데이터베이스 전부·상당 부분의 복제나 작은 부분을 반복적·체계적으로 가져오는 행위는 별도 문제가 될 수 있다.
4. 팀이 새 문장이나 데이터베이스를 만들었다고 해도 원저작물의 권리는 사라지지 않는다. BGG 설명을 번역·요약하거나 이미지를 변형한 결과는 별도 허가 없이 온전히 “우리 자료”가 된다고 단정할 수 없다.

## 1. BGG 승인으로 현재 확인되는 범위

[BGG XML API 이용 조건](https://boardgamegeek.com/wiki/page/XML_API_Terms_of_Use)은 다음을 정한다.

- XML API와 XML API2 데이터는 엄격한 비상업적 목적에서 복제·표시할 수 있다.
- API에서 취득한 데이터와 User Submissions를 수정해서는 안 된다.
- BGG를 데이터 출처로 표시하고, 공개 앱에는 BGG로 연결되는 `Powered by BGG` 로고를 보여야 한다.
- BGG XML API 조건은 AI·LLM 학습을 금지하고, 일반 이용약관은 BGG 사이트 데이터를 AI·LLM 시스템의 데이터로 사용하는 것까지 금지한다.
- Wikipedia가 출처로 지정된 API 텍스트는 별도로 CC BY-NC-SA 3.0 조건이 적용된다.

[BGG XML API 사용 안내](https://boardgamegeek.com/using_the_xml_api)는 애플리케이션 등록·승인을 API 접근과 토큰 발급 절차로 설명한다. 승인된 앱이어야 전체 게임 CSV를 받을 수 있고, 서버 측 요청과 캐싱을 권장하며, 공개 앱의 로고 표시를 다시 요구한다. [BGG XML API 문서](https://boardgamegeek.com/wiki/page/bgg_xml_api)는 전체 게임 CSV도 라이선스 목적상 XML API 데이터로 본다고 명시한다.

따라서 **승인 메일 원문에 번역·요약·변환 허용이 별도로 적혀 있지 않다는 전제**에서는 다음처럼 해석하는 것이 안전하다.

| 행위 | 표준 승인만으로 확인되는가 | 판단 근거 |
| --- | --- | --- |
| 승인된 API·CSV 접근 | 예 | 등록·승인과 토큰·CSV 접근 절차가 명시됨 |
| 비상업적 원본 데이터 복제·표시 | 예 | 표준 라이선스가 허용함 |
| 서버 요청 결과 캐싱 | 예 | 공식 사용 안내가 권장함 |
| 출처 표시 없이 공개 | 아니요 | BGG 명칭과 `Powered by BGG` 로고·링크가 필요함 |
| 필드명·단위·범위·분류 정규화 | 불명확하므로 추가 확인 필요 | “데이터를 수정하지 말라”는 조건과 충돌할 수 있음 |
| BGG 이름·설명의 한국어 번역 | 아니요 | 표준 조건에 수정 금지가 있고 번역 예외가 없음 |
| BGG 설명을 바탕으로 한 한국어 요약·재서술 | 아니요 | 표준 조건에 수정 금지가 있고 보호되는 표현의 2차 이용도 문제됨 |
| BGG 이미지 수정·재호스팅 | 아니요 | 표준 허용 범위와 실제 이미지 권리자를 별도로 확인해야 함 |
| 광고·결제·유료 혜택·모금이 있는 서비스 | 아니요 | 공식 안내가 이를 상업적 이용으로 보고 별도 라이선스를 요구함 |

[BGG 일반 이용약관](https://boardgamegeek.com/terms)도 사이트나 그 일부의 다운로드·수정에는 명시적 서면 동의가 필요하다고 정한다. 제18절은 이용약관·개인정보처리방침이 BGG와 이용자 사이의 전체 합의이며 종전 서신 등을 대체한다고 정하므로, 단순 이메일 회신만 보관하기보다 그 회신이 승인된 애플리케이션·라이선스 기록의 일부이고 표준 수정 금지의 명시적 예외인지 확인받는 편이 안전하다. 한국 저작권법상 개별 사실이 보호되지 않을 수 있다는 판단만으로 BGG와 합의한 이용 조건까지 없어지는 것은 아니다. BGG 이용약관은 Texas 법을 준거법으로 정하므로, 대한민국 저작권법 검토는 BGG 약관의 예외를 만들어 주지 않는다.

## 2. “인터넷 자료를 보고 직접 만들면 우리 자료인가?”

### 객관적 사실과 새 표현

[저작권법 제2조 제1호](https://www.law.go.kr/법령/저작권법/제2조)는 저작물을 인간의 사상 또는 감정을 표현한 창작물로 정의한다. [한국저작권위원회 기초 설명](https://www.copyright.or.kr/information-materials/common-sense/knowledge-for-netizen/index.do)은 아이디어나 사실 그 자체가 아니라 창작적인 표현을 보호한다고 설명한다.

이 기준에 따르면 출시연도, 최소·최대 인원, 통상 플레이 시간처럼 객관적으로 검증 가능한 사실 자체를 어느 한 사람이 독점한다고 보기는 어렵다. 팀이 여러 적법한 출처에서 사실을 확인하고 다음 조건을 지키면 독자 자료로 분리하기 수월하다.

- 원문 설명을 보면서 문장 구조와 표현만 바꾸지 않고, 확인한 사실을 바탕으로 처음부터 새 문장을 쓴다.
- 한 사이트의 목록을 통째로 옮기지 않고 필드별 출처·취득일·검증자를 기록한다.
- 출처의 이용약관·라이선스·접근 제한을 각각 확인한다.
- BGG에서 취득한 필드와 독립 출처에서 조사한 필드를 provenance로 구분한다.
- 사실의 정확성뿐 아니라 본판·확장판·판본 매핑도 사람이 검증한다.

반대로 “공개 페이지를 참고했다”는 말만으로 다음 이용이 허용되지는 않는다.

- BGG나 출판사의 설명문을 복사하거나 문장 순서·단어만 조금 바꾸기
- 원문 설명을 한국어로 번역하거나 그 표현을 유지한 요약문 만들기
- 사진·박스아트·규칙서 삽화·로고를 내려받아 표시하거나 변형하기
- 단일 사이트의 전체 목록 또는 상당 부분을 크롤링·복제하기
- 작은 부분이라도 같은 데이터베이스에서 반복적·체계적으로 추출하기

### 번역·요약과 원저작물

[저작권법 제5조](https://www.law.go.kr/법령/저작권법/제5조)는 원저작물을 번역·변형 등의 방법으로 만든 창작물을 2차적저작물로 보호하면서도 원저작자의 권리에 영향을 주지 않는다고 정한다. [제22조](https://www.law.go.kr/법령/저작권법/제22조)는 원저작자에게 2차적저작물을 작성·이용할 권리를 부여한다. [제46조](https://www.law.go.kr/법령/저작권법/제46조)에 따라 이용허락을 받은 사람도 허락받은 방법과 조건 안에서만 이용할 수 있다.

따라서 팀이 번역문·요약문에 새로운 표현을 더했더라도 원문이 보호되는 설명문이라면 원저작물 이용허락이 별도로 필요할 수 있다. 특히 BGG API 조건은 저작권 판단과 별개로 데이터 수정을 금지하므로, BGG 설명을 출발점으로 하는 번역·요약은 BGG의 명시적 예외 허가를 먼저 받는 편이 안전하다. 숫자 사실만 확인한 뒤 원문에 의존하지 않고 새 설명을 작성한 경우와는 구분해야 한다.

### 데이터베이스 권리

[저작권법 제2조 제17호부터 제20호](https://www.law.go.kr/법령/저작권법/제2조)는 소재의 집합, 창작적인 선택·배열을 가진 편집저작물, 검색 가능한 데이터베이스, 상당한 투자를 한 데이터베이스제작자를 구분한다. [제6조](https://www.law.go.kr/법령/저작권법/제6조)는 편집저작물의 보호가 개별 소재의 권리에 영향을 주지 않는다고 정한다.

[제93조](https://www.law.go.kr/법령/저작권법/제93조)는 데이터베이스제작자에게 전부 또는 상당 부분을 복제·배포·방송·전송할 권리를 주고, 작은 부분도 반복적·체계적 이용으로 일반적 이용과 충돌하거나 제작자의 이익을 부당하게 해치면 상당 부분으로 볼 수 있다고 정한다. 동시에 이 권리는 개별 소재 자체에는 미치지 않는다고 명시한다. 한국저작권위원회가 소개한 [구직정보 데이터베이스 무단 복제 판결](https://www.copyright.or.kr/information-materials/trend/precedents/view.do?brdctsno=50300)도 수만 회 반복 추출을 통상적 이용으로 보지 않았다.

따라서 개별 게임의 객관적 사실을 독립적으로 조사하는 것과 BGG의 전체 게임 목록을 복제·가공하는 것은 같은 행위가 아니다. 후자는 BGG 약관뿐 아니라 데이터베이스 권리 검토가 필요하다. 반대로 팀이 상당한 투자로 독립 데이터베이스를 제작하면 그 데이터베이스에 별도 권리가 생길 여지는 있지만, 그 권리가 원천 설명·이미지의 권리를 지우거나 개별 사실 자체를 팀이 소유한다는 뜻은 아니다.

### “내 자료”와 “팀 자료”도 다르다

[저작권법 제10조](https://www.law.go.kr/법령/저작권법/제10조)에 따라 저작권은 창작할 때 발생하고 원칙적으로 저작자가 권리를 가진다. 법인 등의 명의로 공표되는 업무상저작물은 [제9조](https://www.law.go.kr/법령/저작권법/제9조)의 요건을 충족하면 법인 등이 저작자가 될 수 있다. 따라서 팀원이 작성한 문장과 분류가 자동으로 공동 “팀 소유”가 되는 것은 아니다. 프로젝트가 계속 사용할 수 있도록 작성자, 사용허락 범위, 수정·배포 권한을 팀 규칙이나 기여 약정에 남기는 편이 안전하다.

## 3. Albam Mate에 적용할 출처 분리

| 데이터 종류 | 권리·약관 판단 | 권장 처리 |
| --- | --- | --- |
| BGG `id`, 원문 이름, 연도·인원·시간·aggregate weight | 개별 값은 사실 성격이 강해도 BGG API·CSV에서 취득하면 BGG 조건과 데이터베이스 경계가 적용됨 | BGG provenance 유지, 원본 보존, 정규화·파생 표시의 명시적 허가 요청 |
| BGG category·mechanic·expansion 관계 | 분류·관계의 집합과 대량 이용은 단순 개별 사실보다 데이터베이스 위험이 큼 | 필드 범위를 허가 요청에 명시하고 원본과 팀 분류를 분리 |
| BGG description·User Submission | 창작성 있는 어문 표현일 수 있고 표준 API 조건상 수정 금지 | 번역·요약 허가 전 재가공하지 않음 |
| Wikipedia 출처로 지정된 API 텍스트 | BGG 조건이 CC BY-NC-SA 3.0을 별도 안내함 | 출처 지정 여부와 해당 CC 조건을 행 단위로 기록·준수 |
| BGG image·thumbnail 또는 외부 이미지 | 이미지별 원권리자와 허용 범위가 다를 수 있음 | 표시·캐시·재호스팅·크롭 각각을 BGG에 묻고, 답변이 불충분하면 권리자 또는 허용 라이선스가 확인된 이미지만 사용 |
| 출판사·규칙서 등 독립 출처의 객관적 사실 | 사실 자체와 원 설명·이미지를 구분해야 함 | 사실만 검증해 새 문장으로 작성하고 출처 조건·검증 기록 유지 |
| 팀이 처음부터 작성한 한국어 설명·태그 | 원문 표현에 의존하지 않은 창작 부분은 작성자 측 권리가 될 수 있음 | 작성자·프로젝트 사용권을 기록하고 BGG 파생값과 별도 컬럼·provenance로 관리 |

BGG 허가가 오기 전에는 `raw_bgg_value`와 `team_authored_value`를 분리하고, 번역·요약·정규화된 BGG 파생 필드는 공개 데이터셋에 넣지 않는 것이 안전하다. 허가 회신에는 허용 필드, 허용 변환, 저장·캐시·공개 범위, 이미지 범위, 표시 의무, 현재 라이선스 분류와 향후 수익화 시 재협의 조건을 그대로 보존해야 한다.

## 4. 자체 DB·자체 API로 옮기면 BGG 조건을 피할 수 있는가?

결론은 **아니다**. [BGG XML API 이용 조건](https://boardgamegeek.com/wiki/page/XML_API_Terms_of_Use)은 런타임 호출 방식이 아니라 XML API·XML API2에 대한 접근과 그 데이터의 이용에 적용되며, 비상업적 복제·표시 허용, 수정 금지, 출처·로고 표시 의무를 함께 둔다. [BGG XML API 문서의 `CSV Downloads` 절](https://boardgamegeek.com/wiki/page/bgg_xml_api)는 전체 게임 CSV도 라이선스 목적상 XML API의 일부라고 명시한다. 따라서 BGG에서 한 번 취득한 데이터를 자체 DB에 저장하고 BGG를 더 호출하지 않더라도 그 데이터가 독립 자료로 바뀌거나 조건이 소멸한다고 볼 공식 근거는 없다.

단계별 경계는 다음과 같다.

- **취득:** XML API·XML API2·전체 게임 CSV로 받은 값에는 BGG 조건이 적용된다. [공식 사용 안내의 `Using other parts of our API` 절](https://boardgamegeek.com/using_the_xml_api)은 공개 XML API 외 비공개 API에는 별도 표시나 허가가 없는 한 라이선스를 주지 않는다고 명시한다. 웹사이트에서 직접 내려받거나 크롤링하는 방식도 [BGG 일반 이용약관](https://boardgamegeek.com/terms)의 다운로드·수정 및 자동 접근 제한을 별도로 따라야 한다.
- **저장:** [공식 사용 안내의 `Usage limits` 절](https://boardgamegeek.com/using_the_xml_api)은 서버 측 요청과 응답 캐싱을 권장한다. 이는 저장·캐시가 라이선스 안에서 가능한 운영 방식이라는 뜻이지, 저장 후 조건에서 벗어나거나 데이터 소유권이 이전된다는 뜻은 아니다.
- **변형:** 자체 DB 컬럼으로 옮겼는지와 무관하게 표준 조건은 취득 데이터를 수정하지 말라고 한다. 정규화·번역·요약·재분류는 별도 서면 예외가 확인되기 전에는 허용됐다고 가정할 수 없다.
- **자체 API 제공:** Albam Mate 서버의 API가 Albam Mate 최종 사용자용 클라이언트만 지원하는 내부 전달 계층이라면, 공식 안내가 구분하는 “최종 사용자용 애플리케이션” 구조로 볼 여지는 있다. 그래도 원래 라이선스 범위와 공개 화면의 `Powered by BGG` 의무는 남는다. 반면 다른 개발자·애플리케이션이 BGG 데이터를 가져가도록 범용 데이터 API를 제공하는 것은 [공식 사용 안내의 `Third party tools for using the API` 절](https://boardgamegeek.com/using_the_xml_api)이 명시적으로 금지하는 제3자 데이터 서비스에 해당할 가능성이 높다.

독립 인터넷 조사 자료는 출처 단계에서 분리해야 한다. BGG 사이트·API·CSV를 사용하지 않고 다른 출처에서 사실을 독립 검증해 새 문장과 분류를 만든 부분에는 BGG XML API 조건이 적용된다고 볼 근거가 없다. 다만 BGG ID·BGG 매핑·BGG 설명을 출발점으로 삼은 값을 단순히 다시 쓰거나 자체 API를 거쳤다는 이유만으로 독립 자료라고 표시할 수는 없다. 다른 출처의 권리·약관은 BGG 문서가 허가해 주지 않으므로 별도로 확인해야 한다.

## 5. 기존 승인 메일에 보낼 영어 회신 초안

**Subject:** Re: Albam Mate XML API application — data transformation permission and license classification

```text
Hello BoardGameGeek team,

Thank you for approving Albam Mate's XML API application.

Albam Mate is an early, student-led project in South Korea, built with limited resources and planned for public operation. We do not currently have any monetization, but our future monetization model has not yet been decided. Our service is intended to help people discover board games and organize in-person gatherings; it is not a board-game store, marketplace, or convention or tournament ticketing service. We hope, in a small but meaningful way, to contribute to Korea's board-game community and industry. We want to build responsibly and respect the work BGG has invested in its community and data, which is why we are asking for clear permission before proceeding.

We understand that the standard XML API Terms allow non-commercial reproduction and display, but state that data retrieved through the API may not be modified. Our requested catalog fields are limited to game IDs, primary and alternate names, year published, minimum and maximum player counts, minimum and maximum playtime, categories, mechanics, aggregate weight, images and thumbnails, descriptions, and expansion relationships. To avoid any misunderstanding, we are requesting express written permission for the following limited uses in Albam Mate, a planned public web service for organizing offline board-game gatherings:

1. Cache and store only those catalog fields from the approved BGG XML API/XML API2 and the all-games CSV in our database.
2. Normalize those fields into our service schema, including player-count and play-time ranges, categories/mechanics, aggregate weight, and base-game/expansion relationships.
3. Manually create Korean translations of game names and descriptions.
4. Manually create concise Korean summaries based on BGG descriptions and factual metadata.
5. Combine BGG-sourced fields with independently researched, team-authored Korean content while keeping field-level source records.
6. Display and search the resulting translated, summarized, and normalized catalog in our public application.

Could you please confirm whether BGG grants Albam Mate an exception to the no-modification restriction for each of the uses above?

Please also clarify:

- whether the permission covers the all-games CSV as well as XML API/XML API2 data;
- whether the requested descriptions are treated as User Submissions and are covered by the permission;
- whether BGG images and thumbnails may be displayed, cached, rehosted, resized, or cropped, and which of those uses require separate permission from the image rightsholder;
- whether any attribution is required in addition to the linked "Powered by BGG" logo and identifying BGG as the source;
- whether any specific fields or transformations must be excluded;
- whether BGG classifies our current and planned use as commercial or non-commercial; and
- if a commercial license is required, whether a low-cost, waived, or deferred license is available for a student-led, early-stage service with limited resources.

Albam Mate currently has no ads, payments, subscriptions, paid benefits, merchandise sales, sponsorships, donations, or other fundraising. Before introducing any monetization, including ads, payments, subscriptions, sponsorships, or donations, we will contact BGG and obtain or update the appropriate license. We will make requests server-side, minimize traffic, cache responses, maintain source records, and display the linked "Powered by BGG" logo. Unless BGG separately authorizes it in writing, we will not scrape private endpoints or provide BGG data as training data or other input to any AI or LLM system.

Our scope is limited to `boardgame` records and, only where needed to represent expansion relationships, `boardgameexpansion` records. We will not use user profiles, collections, individual ratings or reviews, comments, plays, forums, marketplace data, or any private or non-public endpoint.

If permission is granted, we would appreciate a reply that explicitly identifies the permitted data sources, fields, transformations, storage/caching, public display, image handling, and attribution requirements so that we can preserve the scope accurately in our project records.

Because Section 18 of the BGG Terms of Service states that the Terms supersede prior correspondence, please also confirm that this written permission will be recorded as part of our approved application/license and constitutes an express exception to the XML API no-modification restriction for the uses you approve.

Thank you,
[Name]
Albam Mate
[Approved application name or ID]
```

## 공식 자료

- [BGG XML API 이용 조건](https://boardgamegeek.com/wiki/page/XML_API_Terms_of_Use)
- [BGG XML API 사용 안내](https://boardgamegeek.com/using_the_xml_api)
- [BGG XML API·전체 게임 CSV 문서](https://boardgamegeek.com/wiki/page/bgg_xml_api)
- [BGG 일반 이용약관](https://boardgamegeek.com/terms)
- [국가법령정보센터 저작권법](https://www.law.go.kr/법령/저작권법)
- [한국저작권위원회: 네티즌이 알아야 할 저작권](https://www.copyright.or.kr/information-materials/common-sense/knowledge-for-netizen/index.do)
- [한국저작권위원회: 저작권 상담사례 100+](https://www.copyright.or.kr/information-materials/common-sense/counsel-case/index.do)
- [한국저작권위원회: 구직정보 데이터베이스 무단 복제 판결 소개](https://www.copyright.or.kr/information-materials/trend/precedents/view.do?brdctsno=50300)
