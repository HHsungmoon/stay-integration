---
paths:
  - "app/src/main/java/**/supplier/**/*.java"
---

# 공급사 어댑터 작성 규칙

이 패키지의 존재 이유는 **공급사 형식이 도메인으로 새지 않게 막는 것**이다.

## 경계 (ArchUnit이 빌드에서 강제한다)

- **공급사 전용 DTO는 자기 어댑터 패키지 밖으로 나가지 않는다.**
- `supplier..`는 **JPA 영속성 API·repository에 의존하지 않는다.** 어댑터와 정규화는 DB를 모른다.
- `search`·`catalog`는 `supplier.adapter..`를 모른다 — 포트만 안다.

어댑터 하나가 (1) WebClient 호출 (2) 역직렬화 (3) 실패 판정 (4) 표준 모델 변환을 **자기 안에서 전부 끝낸다.**

**신규 공급사 추가 시 고치는 것**: 어댑터 구현체 1개 + 설정 + 레지스트리 등록.
도메인·검색 서비스·응답 모델은 무변경. **이 사실이 깨지면 경계가 잘못 그어진 것이다.**

## 실패는 예외가 아니라 값으로

각 호출 결과를 `SupplierResult<T>`로 변환한다 — sealed, `Success<T>` · `Failure<T>` · `Skipped<T>` 셋.
예외로 전파하면 스트림 하나가 죽을 때 전체가 죽어 **부분 실패 허용이 구조적으로 불가능**해진다.

제네릭인 이유: 카탈로그(`List<CatalogProperty>`)와 재고·요금(`List<Offer>`)이 같은 타입으로 다뤄져
관측성 지표가 한 곳에서 나온다. `Skipped`(서킷 열림 등 **호출하지 않음**)는 처음부터 있다 —
이 타입을 다루는 switch는 세 케이스를 모두 처리해야 컴파일된다. `default`를 쓰지 않는다.

이 구조 하나가 **실패 판정 통일**을 자동으로 달성한다 —
HTTP 상태로 실패를 알리는 공급사와 **항상 200을 주고 본문 코드로만 알리는 공급사**가
같은 실패 값으로 번역되기 때문이다. 본문 코드를 확인하지 않으면 장애가 "빈 결과"로 둔갑한다.

실패 분류는 `FailureKind` 8값(CLAUDE.md 표) — `TIMEOUT` `CONNECTION_FAILED` `SERVER_ERROR` `RATE_LIMITED`는 재시도 가능,
`UNAUTHORIZED` `BAD_REQUEST` `UNEXPECTED` `NORMALIZATION_FAILED`는 불가. **모르는 코드는 `UNEXPECTED`** — 재시도하지 않는다.

**항목 단위 결함은 격리한다.** `fetchAvailability`는 `SupplierResult<AvailabilityResult>`를 돌려주고, 음수 요금·통화 누락·날짜 형식 오류 같은
항목 결함은 `rejected`에 넣고 나머지 상품은 `offers`로 살린다. 응답 전체가 못 쓸 때(파싱 불가·봉투 이상·`resultCode` 실패)만 `Failure`.

`SupplierResult`에 소요시간을 담아 둔다 — 관측성 지표가 여기서 파생된다.

## 호출

- **WebClient만 쓴다.** `RestClient`·`RestTemplate` 금지.
- **`retrieve()`가 아니라 `exchangeToMono()`.** `retrieve()`는 4xx/5xx를 예외로 바꿔 에러 본문을 detail에 담기 번거롭고,
  항상 200을 주는 공급사에는 아무것도 걸러주지 않는다. 상태와 본문을 한 곳에서 보고 직접 판정한다.
- 타임아웃 두 계층을 어댑터가 소유한다: 연결 **500ms**(Netty `CONNECT_TIMEOUT_MILLIS`) / 응답 **2s**.
  응답은 **Netty `responseTimeout` + Reactor `.timeout()` 두 겹** — 전자는 헤더까지, 후자가 느린 본문을 잡는다.
  오케스트레이션 데드라인 **3s**는 병합 쪽(`search`)에 건다.
- 값은 `supplier.endpoints.<id>`에서 **공급사 단위로** 읽는다. 항목이 없으면 기동 실패(fail-fast).
- 재고·요금 조회는 **한 번에 최대 50개**. 초과하면 **호출 없이** `Failure(BAD_REQUEST)` — 뻔히 실패할 호출을 보내지 않는다.
- 인증은 `X-Api-Key` 헤더. 키도 공급사 단위 설정.
- `elapsed`는 성공·실패 모두 담는다 — 관측성 지표가 여기서 파생된다.
- 호출 골격은 `support.SupplierCallPipeline`(공급사 단위 인스턴스)이 소유한다 — 소요시간 측정, Reactor 타임아웃, 빈 응답 방어, 예외 → `Failure` 강등.
  어댑터는 `exchangeToMono` 안에서 상태·본문을 보고 `CallOutcome`(Ok/Failed)만 만든다. elapsed는 파이프라인이 마지막에 찍는다.
- **서킷 브레이커는 어댑터 파이프라인의 맨 바깥에 있다.** 열렸을 때 `Skipped("circuit open")`을 돌려주는 것은 "이 공급사 호출의 결과"라 어댑터가 표현한다.
  파이프라인은 어댑터가 직접 만들지 않고 `support.SupplierCallPipelines.forSupplier(id)`에서 받는다 — 타임아웃·서킷 조립이 한 곳이고 신규 공급사가 서킷을 자동으로 얻는다.
  서킷은 **공급사당 하나**(카탈로그·재고 공유). **어댑터가 실패를 값으로 돌려주므로 `CircuitBreakerConfig.recordResult` 술어가 반드시 있어야 한다** —
  없으면 모든 `Failure`가 성공으로 기록되어 서킷이 영원히 열리지 않고, 그 사실이 조용하다. 기록 기준은 `FailureKind.retryable()`(재시도 판단과 같은 질문).
  50개 초과 사전 거절은 파이프라인 밖이라 서킷 통계에 들어가지 않는다.
- 공급사 DTO는 **날짜를 `String`, 금액을 boxed(`Long`)로** 받는다. `LocalDate`로 받으면 항목 하나의 날짜 오류가 본문 전체 파싱 실패가 되고,
  primitive면 누락이 0으로 둔갑한다. 파싱·누락 판정은 normalizer가 항목 단위로 한다.

## 정규화는 순수 함수로 유지한다

외부 I/O·DB 접근 없이. 어느 스레드에서 돌아도 안전하고 단위 테스트가 쉽다.

- 날짜별 단가를 주는 공급사: `Σ(단가 + 세금)`으로 gross 상향 정규화. 총액 필드를 주지 않으므로 이 합이 곧 총액이다 —
  "합계와 총액 대조"는 비교 대상이 없어 성립하지 않는다.
- 총액만 주는 공급사: 그대로 쓴다. **총액을 숙박일수로 나눠 단가를 만들지 않는다**(없는 값을 지어내는 것).
- **연박 재고 = 요청 기간 내 날짜별 잔여 수의 최솟값**(D-5).
  어느 하루라도 0이면 전체 0. **응답에 빠진 날짜는 0으로 간주**(과판매 방지).
  범위 밖 날짜는 무시, 음수는 0으로 clamp.
- 조식 포함 여부는 객실 타입이 아니라 **Offer의 속성**이다(D-4).
- 매핑에 없는 상품이 오면 **해당 항목만 제외 + 경고 로그 + 카운터**(D-10). 전체 응답을 실패시키지 않는다.
- 이름·최대 수용 인원은 **재고·요금 응답 값**을 쓴다(D-11). 숙소 목록 값과 다르면 로그.
