# 공급사 어댑터 — 설계

작성일: 2026-09-05
상태: 구현 완료 (2026-09-05). 구현하면서 설계와 달라진 곳은 §14에 모았다.

`supplier.adapter.<name>` 패키지. WebClient로 공급사를 호출하고, 응답을 역직렬화하고, 실패를 판정하고,
표준 모델로 변환한다 — **이 네 가지를 어댑터 안에서 전부 끝낸다.** 공급사의 필드명·래핑·실패 표현은
이 패키지 밖으로 한 발도 나가지 않는다.

## 요약 — 어댑터는 무엇을 하는가

지금까지 `catalog`는 포트(인터페이스)만 보고 있고, 실제로 HTTP를 치는 것은 아무것도 없다. 어댑터가 그 빈자리다.
공급사 A는 `hotelCodes`·`dailyRates`·HTTP 상태 코드로, B는 `propertyIds`·`totalPrice`·항상 200 + `resultCode`로
말하는데, 어댑터가 그 둘을 **같은 `Offer`와 같은 `SupplierResult`로 번역**한다. 그래서 `search`는 공급사가 몇 개든,
각자 어떻게 다르든 한 가지 코드로 병합한다.

번역에서 가장 중요한 것 셋: **요금** — A는 날짜별 net+tax를 더해 gross 총액을 만들고, B는 총액을 그대로 쓴다.
총액을 박수로 나눠 단가를 만들지는 않는다(없는 값). **재고** — 요청 기간 날짜별 잔여 수의 최솟값, 빠진 날짜는 0.
**실패** — A의 503도 B의 200+E503도 같은 `Failure(SERVER_ERROR)`가 된다. 본문 코드를 보지 않으면 B의 장애는
"빈 결과"로 둔갑한다. 이 단계가 끝나면 처음으로 **Mock 서버를 붙여 카탈로그가 실제로 채워지는 것**을 볼 수 있다.

---

## 0. 이 단계의 범위

| 만드는 것 | 패키지 |
|---|---|
| `fetchAvailability()` 추가, `AvailabilityQuery`, `Offer`·`Price`·`PriceDetail`·`NightlyRate`, `AvailabilityResult`·`NormalizationIssue` | `supplier.port` |
| `FailureKind` 세분화 (3 → 8) + `retryable()` | `supplier.port` (기존 타입 변경 — §3) |
| `InventoryRule` — 연박 재고 판정 순수 함수 | `supplier.normalization` |
| `SupplierProperties`, `SupplierWebClients`, `FailureClassifier`, `SupplierCallPipeline`, `CallOutcome`, `ItemDefect` | `supplier.adapter.support` (§14) |
| `SupplierAAdapter`, `SupplierAResponse`(DTO), `SupplierANormalizer` | `supplier.adapter.a` |
| `SupplierBAdapter`, `SupplierBResponse`(DTO), `SupplierBNormalizer` | `supplier.adapter.b` |
| yaml `supplier.endpoints.{a,b}` | `application.yaml` |
| `spring-boot-starter-webflux` → **`spring-boot-starter-webclient`** 교체 (§6.1) | `app/build.gradle.kts` |

**하지 않는 것**: 재시도(버리는 순서 1위, 설계로만), 서킷 브레이커(7단계 — 다만 들어갈 자리는 지금 정한다 §9),
내부 ID 변환(`search`가 `CatalogLookup`으로 한다 — 어댑터는 DB를 모른다, 경계 3), 청크 분할(`search`).

**기존 코드가 바뀌는 곳**: `FailureKind` 값이 바뀌므로 `CatalogSyncService`와 `CatalogSyncServiceTest`가 따라 바뀐다.
`SupplierAdapter`에 메서드가 추가되므로 테스트 stub도. 이건 기능 커밋 앞에 **선행 refactor 커밋**으로 분리한다(규칙 8).

---

## 1. 표준 모델 — `supplier.port`

### 1.1 `AvailabilityQuery`

```java
public record AvailabilityQuery(List<String> hotelCodes, LocalDate checkIn, LocalDate checkOut, int adults, int children) {
    public static final int MAX_CODES = 50;
    public int nights();
    public List<LocalDate> stayDates();     // checkIn 부터 checkOut 전날까지
}
```

검증(`checkOut > checkIn`, 인원, 30박)은 `search` 컨트롤러가 한다(D-13). 여기서는 `nights() >= 1`만 생성자에서 보장한다.
**50개 초과는 어댑터가 호출 전에 거절한다**(§5.3).

### 1.2 `Offer` — 재고·요금 응답 1건의 표준형

```java
public record Offer(
    SupplierId supplier,
    String hotelCode, String hotelName,             // 공급사 코드. 내부 ID 변환은 search가 lookup으로
    String roomTypeCode, String roomTypeName,
    int maxOccupancy,                                // D-11: ② 값을 쓴다
    int availableRooms,                              // D-5 판정 결과. 0 = 매진
    Price price,                                     // 필수 — 모든 공급사가 채운다
    PriceDetail priceDetail                          // nullable — 줄 수 있는 공급사만
) {}

public record Price(String currency, long totalAmount, boolean taxIncluded, boolean breakfastIncluded, int nights) {}
public record PriceDetail(long taxAmount, List<NightlyRate> nightlyBreakdown) {}
public record NightlyRate(LocalDate date, long net, long tax, int remainingRooms) {}
```

D-1의 2계층이 그대로다. `Price`는 A·B 모두 채울 수 있는 공통 분모(기간 총액·gross), `PriceDetail`은 A만 채운다.
`nights`를 담는 이유는 D-3 — 1박 평균 단가를 우리가 제공하지 않고 필요한 쪽이 계산하게 한다.

**어댑터는 공급사 코드를 그대로 담는다.** 내부 식별자를 몰라야 하기 때문이다(경계 3 — `supplier..`는 DB를 모른다).
`search`가 `CatalogLookup.find(supplier, hotelCode, roomTypeCode)`로 바꾼다.

### 1.3 `AvailabilityResult` — 항목 단위 정규화 실패를 격리한다

```java
public record AvailabilityResult(List<Offer> offers, List<NormalizationIssue> rejected) {}
public record NormalizationIssue(String hotelCode, String roomTypeCode, String reason) {}
```

**한 항목이 이상하다고 그 공급사의 다른 상품까지 버리지 않는다.** 응답 전체가 못 쓸 상태(파싱 불가, 봉투가
다름, `resultCode` 실패)일 때만 `Failure`이고, 항목 하나의 결함(음수 요금, 통화 누락, B의 `taxIncluded=false`)은
그 항목만 `rejected`에 넣고 나머지는 `offers`로 살린다. 부분 실패 허용의 정신을 항목 단위까지 내린 것이고,
선택 구현 "정규화 실패 데이터 격리"의 최소 버전이다. `rejected`는 관측성에서 `normalization_error`로 센다.

`fetchAvailability`의 반환은 `Mono<SupplierResult<AvailabilityResult>>`. 카탈로그는 항목 단위 실패가 드물어
`SupplierResult<List<CatalogProperty>>` 그대로 둔다.

---

## 2. 포트 확장

```java
public interface SupplierAdapter {
    SupplierId id();
    Mono<SupplierResult<List<CatalogProperty>>> fetchCatalog();
    Mono<SupplierResult<AvailabilityResult>> fetchAvailability(AvailabilityQuery query);   // 추가
}
```

**어댑터는 예외를 던지지 않는다.** 모든 결과는 `Success`·`Failure`·`Skipped` 중 하나다. 이 계약 덕에 `search`가
`onErrorResume` 없이 병합할 수 있고, 지금 `CatalogSyncService`에 있는 `onErrorResume(unexpected)`은 계약이 깨졌을 때의
안전망일 뿐이다.

---

## 3. `FailureKind` 세분화 — 3개에서 8개로

현재 `TRANSIENT / PERMANENT / NORMALIZATION`은 재시도 여부만 말한다. 관측성 지표(`outcome` 태그)가 `timeout`·`auth_error`
같은 세분화를 요구하고, 로그에서 "왜 실패했나"를 읽으려면 원인이 필요하다. 두 enum(원인 + 재시도 분류)을 두는 대신
**원인 enum 하나에 `retryable()` 메서드**를 둔다 — 재시도 정책은 이 메서드만 본다.

| `FailureKind` | 재시도 | A (HTTP) | B (`resultCode`) | 예외 |
|---|---|---|---|---|
| `TIMEOUT` | O | — | — | 응답 타임아웃, 오케스트레이션 데드라인 |
| `CONNECTION_FAILED` | O | — | — | 연결 거부·리셋, DNS 실패 |
| `SERVER_ERROR` | O | 500, 503, 그 외 5xx | `E500`, `E503` | — |
| `RATE_LIMITED` | O | 429 | `E429` | — |
| `UNAUTHORIZED` | X | 401 | `E401` | — |
| `BAD_REQUEST` | X | 400 (`TOO_MANY_HOTEL_CODES` 포함) | `E400` | 어댑터의 50개 사전 거절 |
| `UNEXPECTED` | X | 그 외 4xx, 파싱 불가 본문 | 모르는 코드, 봉투 형태 이상 | 어댑터가 예외를 던짐(계약 위반) |
| `NORMALIZATION_FAILED` | X | 응답 전체를 표준 모델로 못 바꿈 | 동일 | — |

**모르는 것은 재시도하지 않는다.** B가 `E999`를 주면 그게 일시적인지 영구적인지 모른다. 재시도 예산은 데드라인 안에
가둬야 하므로, 이해하지 못하는 실패에 예산을 쓰지 않고 크게 로그를 남긴다. 다만 B의 모르는 코드가 `E5`로 시작하면
`SERVER_ERROR`, `E4`로 시작하면 `BAD_REQUEST`로 관대하게 분류한다 — 공급사가 코드 체계를 HTTP에 맞춰 짰다는 가정이고,
그 가정은 detail에 남긴다.

관측성 `outcome` 태그는 `kind.name().toLowerCase()`로 파생한다 — 08에서 그대로 구현됐다. 카디널리티는 8 + `success`.
`Skipped`는 `outcome` 값이 아니라 **별도 Counter**(`supplier.call.skipped`)다 — 지연 0을 Timer에 넣으면 percentile을 왜곡한다(08 §3).

---

## 4. 연박 재고 판정 — `supplier.normalization.InventoryRule`

두 공급사가 모두 날짜별 재고를 주므로 판정 로직은 하나다. **순수 함수**로 두고 두 어댑터가 공유한다.

```java
public final class InventoryRule {
    // D-5: 요청 기간 내 최솟값. 빠진 날짜는 0. 범위 밖은 무시. 음수는 0.
    public static int availableRooms(List<LocalDate> stayDates, Map<LocalDate, Integer> remainingByDate);
}
```

| 입력 | 결과 | 이유 |
|---|---|---|
| `[3, 1, 5]` | 1 | 3박 모두 남아 있어야 한다 |
| `[2, 0, 4]` | 0 | 하루라도 0이면 매진 |
| 요청 3박, 응답 2일치 | 0 | **빠진 날짜는 0** — 팔 수 없는 것을 파는 것보다 못 파는 것이 낫다 |
| 요청 범위 밖 날짜 포함 | 무시 | 스펙상 없어야 하지만 방어 |
| `-1` | 0 | 음수 clamp |

`supplier.port`가 아니라 별도 `normalization` 패키지인 이유 — 포트는 계약(인터페이스·record)만 두고, 로직은 따로.
어댑터 패키지 안에 두지 않는 이유 — A·B가 공유하는데 한쪽 어댑터 패키지에 두면 다른 쪽이 그 패키지에 의존하게 되어
경계 1(어댑터 패키지 밖으로 나가지 않는다)이 흐려진다.

---

## 5. 어댑터 내부

### 5.1 호출 파이프라인 (A·B 공통 골격)

```
Mono.defer(() -> {
    long started = System.nanoTime();
    return webClient.get().uri(...).header("X-Api-Key", key)
        .exchangeToMono(response -> ...)            // 상태 코드와 본문을 함께 본다 — B는 200이어도 본문을 봐야 한다
        .timeout(responseTimeout)                    // 응답 타임아웃 (D-7 2s). Netty responseTimeout과 이중으로 — §5.2
        .map(body -> normalize(body) → Success(elapsed))
        .onErrorResume(e -> Mono.just(Failure(classify(e), elapsed)));   // 예외를 값으로 강등. 어댑터는 던지지 않는다
})
```

`retrieve()`가 아니라 `exchangeToMono()`를 쓰는 이유 — `retrieve()`는 4xx/5xx를 예외로 바꾸는데, 그러면 A의 에러 본문
(`{error, message}`)을 읽어 detail에 담기 번거롭고, B는 항상 200이라 `retrieve()`가 아무것도 걸러주지 않는다.
상태와 본문을 한 곳에서 보고 직접 판정하는 편이 두 공급사에 같은 골격을 쓸 수 있다.

`elapsed`는 성공·실패 모두 담는다. 관측성 지표가 여기서 파생된다.

### 5.2 타임아웃 — 어댑터가 소유하는 두 계층

| 계층 | 값 | 어디에 |
|---|---|---|
| 연결 | 500ms | Reactor Netty `HttpClient.option(CONNECT_TIMEOUT_MILLIS)` |
| 응답 | 2s | Netty `responseTimeout` **그리고** Reactor `.timeout()` |

응답 타임아웃을 두 겹으로 거는 이유 — Netty의 `responseTimeout`은 응답 헤더 수신까지를 재고, 본문이 느리게 오는 경우는
Reactor `.timeout()`이 잡는다. 하나만 두면 "헤더는 왔는데 본문이 안 오는" Mock의 `delay`·`no-response` 변종을 놓친다.
값은 공급사별 설정(§6)에서 읽는다. 오케스트레이션 데드라인(3s)은 `search`의 것이고 여기 없다.

### 5.3 50개 초과는 호출 없이 거절

`query.hotelCodes().size() > 50`이면 네트워크를 타지 않고 `Failure(BAD_REQUEST, "51 codes > 50")`을 즉시 돌려준다.
청크 분할은 `search`의 책임이지만, 어댑터가 공급사의 계약을 알고 있는데 뻔히 실패할 호출을 보낼 이유가 없다.
Mock의 50개 검증(청크 분할의 회귀 테스트)은 이 사전 거절을 지우면 그대로 살아 있다.

### 5.4 A — HTTP 상태로 판정, 날짜별 단가를 합산

```
status 2xx → SupplierAResponse.Availability → items 각각:
    dates = query.stayDates()
    remaining = dailyRates → Map<date, remainingRooms>
    availableRooms = InventoryRule.availableRooms(dates, remaining)
    total = Σ over dailyRates in range (nightlyRate + taxAmount)        // net → gross 상향 (D-2)
    tax   = Σ taxAmount
    price = Price(currency, total, taxIncluded=true, breakfastIncluded, nights)
    priceDetail = PriceDetail(tax, nightlyBreakdown)                     // A는 채운다
    항목 결함(음수·통화 누락·날짜 파싱 실패) → rejected
status 4xx/5xx → body SupplierAResponse.Error → Failure(kind by status, detail = error + message)
```

**요구사항 문서의 "날짜별 합계가 총액과 일치하는지 검증"은 성립하지 않는다.** A는 총액 필드를 주지 않는다 — 우리가
합산해서 만드는 값이니 비교 대상이 없다. 그 문장은 요구사항 문서에서 정정한다(§11).

`dailyRates`에 요청 기간 **밖** 날짜가 있으면 재고 판정에서도 요금 합산에서도 무시한다. 요청 기간 안의 날짜가 **빠지면**
재고는 0(매진)이 되고, 요금은 있는 날짜만 합산된다 — 매진 상품의 요금이라 실질 영향은 없지만, 이 경우 `rejected`가
아니라 `offers`에 `availableRooms=0`으로 들어간다는 것을 명시한다.

### 5.5 B — 본문 `resultCode`로 판정, 총액은 그대로

```
status != 2xx → 스펙상 없어야 하지만 A와 같은 규칙으로 방어
status 2xx → SupplierBResponse.Envelope<Search>:
    resultCode != "0000" → Failure(kind by code, detail = resultCode + resultMessage)   // data는 null
    resultCode == "0000" → data.items 각각:
        availableRooms = InventoryRule.availableRooms(dates, inventory → Map)
        price = Price(currency, totalPrice, taxIncluded, breakfastIncluded, nights)      // 그대로 (D-2)
        priceDetail = null                                                              // B는 날짜별 값이 없다
        taxIncluded == false → rejected ("gross 가정이 깨짐 — 세금액 없이 상향 불가")
```

`data`가 `null`인 실패 응답을 `Envelope<Search>`로 역직렬화하면 `data`만 null이고 나머지는 채워진다. Jackson 3이
`null`을 그대로 둔다는 것을 어댑터 테스트가 확인한다.

### 5.6 카탈로그 조회

같은 골격이고 정규화가 단순하다 — A `items[].{hotelCode, hotelName, roomTypes[]}`, B `data.items[].{propertyId, propertyName, rooms[]}`
→ `CatalogProperty`. 공급사가 코드를 비워 보내면 그 항목은 건너뛰고 `warn`. (카탈로그는 `AvailabilityResult`처럼
`rejected`를 두지 않는다 — 발생 빈도가 낮고, 동기화 리포트에 숫자로 담을 자리도 없다. 필요해지면 그때.)

---

## 6. 설정 — `supplier.adapter.support.SupplierProperties`

```yaml
supplier:
  endpoints:
    a:
      base-url: http://localhost:9090
      api-key: mock-key-a
      connect-timeout: 500ms      # 같은 네트워크 대역의 정상 연결은 수십 ms. 이 이상은 회복이 아니라 대기다
      response-timeout: 2s        # 검색 전체 목표 3s에서 병합·직렬화 여유를 뺀 값
    b:
      base-url: http://localhost:9090
      api-key: mock-key-b
      connect-timeout: 500ms
      response-timeout: 2s
```

`Map<String, Endpoint>`를 공급사 id로 키잉한다. 어댑터는 생성자에서 자기 id의 항목을 꺼내고, **없으면 기동 실패**(fail-fast).
07에서 같은 record에 공급사 공통 `circuit` 설정이 추가됐다(`supplier.circuit.*`, 역시 필수).
Docker Compose에서는 `SUPPLIER_ENDPOINTS_A_BASE_URL=http://mock-supplier:9090`으로 덮는다 — 컨테이너 안에서는
`localhost`가 자기 자신이라 지금부터 외부화해 둔다.

`SupplierWebClients`가 항목마다 Netty `HttpClient`(연결 타임아웃·응답 타임아웃)를 붙인 `WebClient`를 만든다.

### 6.1 `WebClient.Builder`는 Boot 4에서 자동설정되지 않는다 — 확인 결과 (2026-09-05)

`spring-boot-starter-webflux`만으로는 `WebClient.Builder` 빈이 없다. Boot 4가 WebClient 자동설정을 **`spring-boot-webclient`**
별도 모듈로 쪼갰고, 그 모듈은 클래스패스에 없었다(`:app:dependencies`로 확인 — `spring-boot-webflux`·`spring-boot-reactor-netty`만 있음).
3.x 지식대로 `WebClient.Builder`를 주입받으면 기동에 실패한다.

**결정: `spring-boot-starter-webflux`를 `spring-boot-starter-webclient`(BOM 관리 4.1.1)로 교체한다.**

| 검토한 선택지 | |
|---|---|
| `spring-boot-starter-webclient` 추가 | `WebClient.Builder` 빈 + Jackson 3 코덱 + **Micrometer 계측 자동 적용**(8단계에서 WebClient 지표가 공짜로 붙는다) |
| `WebClient.builder()` 직접 생성 | 의존성 추가 없음. 대신 계측을 나중에 수동으로 붙여야 한다 |

교체하는 이유가 하나 더 있다 — `starter-webflux`는 WebFlux **서버** 자동설정까지 끌고 온다(MVC와 같이 있으면 MVC가 이기지만).
우리 의도는 "WebClient만, WebFlux 전면 도입 아님"이고, `starter-webclient`가 그 의도를 의존성 이름 그대로 말한다.
공급사별 타임아웃은 주입받은 `Builder`를 `clone()`해 커넥터만 바꿔 붙인다. 테스트 쪽 `starter-webflux-test`도
`starter-webclient-test`로 바꿀 수 있는지 구현 시 확인한다.

---

## 7. 신규 공급사 추가 — 정말 세 곳인가

이 단계에서 그 약속이 처음으로 검증된다.

| 고치는 것 | 어디 |
|---|---|
| 어댑터 구현체 1개 (+ DTO, normalizer) | `supplier.adapter.<name>` |
| 설정 | `supplier.endpoints.<name>` |
| 레지스트리 등록 | 어댑터 클래스의 `@Component` — 컴포넌트 스캔이 `SupplierRegistry`에 넣는다 |

`SupplierId`가 String이어서 enum 수정이 없고, `FailureKind`는 공급사 중립이라 그대로다. `search`·`catalog`·응답 DTO는
무변경. **이 표가 거짓이 되면 경계가 잘못 그어진 것이다.** ArchUnit(06 단계에서 추가)이 `..adapter..` 밖에서 `..adapter.<name>..`을
참조하는 코드를 잡는다. 07 이후 서킷도 `SupplierCallPipelines.forSupplier(id)`로 자동으로 붙으므로 표는 그대로다.

---

## 8. 패키지

```
supplier/
├── port/            SupplierAdapter · SupplierId · SupplierResult · FailureKind · SupplierRegistry
│                    CatalogProperty · CatalogRoomType
│                    AvailabilityQuery · Offer · Price · PriceDetail · NightlyRate · AvailabilityResult · NormalizationIssue
├── normalization/   InventoryRule                       A·B 공유 순수 함수
└── adapter/
    ├── support/     SupplierProperties · SupplierWebClients · FailureClassifier        어댑터끼리 공유. 도메인은 모른다
    │                SupplierCallPipeline · CallOutcome · ItemDefect                   호출 골격과 그 안에서 쓰는 값(§14)
    │                SupplierCallPipelines(07 — 서킷 레지스트리 + 파이프라인 팩토리) · SupplierCircuitMetricsConfig(08 — 지표 바인딩)
    ├── a/           SupplierAAdapter · SupplierAResponse(DTO record 묶음) · SupplierANormalizer
    └── b/           SupplierBAdapter · SupplierBResponse · SupplierBNormalizer
```

`supplier`에는 `controller/service/...` 서브패키지 컨벤션을 적용하지 않는다 — HTTP를 받는 계층이 아니라 부르는 계층이고,
`port / normalization / adapter.<name>`이 역할 구분이다. 어댑터 패키지 안은 클래스가 셋이라 더 나누지 않는다.

`SupplierANormalizer`를 어댑터에서 분리하는 이유 — **정규화는 순수 함수**(스레드 모델 3)라 WebClient 없이 단위 테스트한다.
어댑터는 "호출 + 판정 + normalizer 호출"만 남는다.

---

## 9. 서킷 브레이커가 들어갈 자리 (7단계, 지금 정해 둔다)

**어댑터 안**, 파이프라인의 맨 바깥이다.

```
Mono.defer(...)                                   // 호출 파이프라인 (§5.1)
    .transformDeferred(CircuitBreakerOperator.of(breaker))
    .onErrorResume(CallNotPermittedException.class, e -> Mono.just(Skipped(...)))
```

`search`가 아니라 어댑터인 이유 — 서킷은 공급사 단위이고, 열렸을 때 `Skipped`를 돌려주는 것은 "이 공급사 호출의 결과"다.
어댑터가 자기 결과 타입으로 표현하는 것이 자연스럽고, `search`는 `Skipped`를 다른 결과와 똑같이 병합하면 된다.
골격은 구현 시점에 `support.SupplierCallPipeline`으로 뺐다(§14) — 서킷은 이 클래스의 `execute()` 안, `timeout` 바깥에 들어간다.

> **07에서 구현됨(2026-09-05).** 예상대로 `execute()`의 맨 바깥에 `CircuitBreakerOperator`가 붙었고, 파이프라인 생성은 `SupplierCallPipelines` 팩토리로
> 옮겨 어댑터 생성자가 `(SupplierWebClients, SupplierCallPipelines)`가 됐다. 어댑터 본문은 무변경. 상세는 `docs/07-circuit-breaker.md`.

---

## 10. 테스트

| 층 | 대상 | 검증 |
|---|---|---|
| 단위 | `InventoryRule` | §4 표 전부 |
| 단위 | `SupplierANormalizer` | `Σ(net+tax)`, `priceDetail` 채움, 범위 밖 날짜 무시, 빠진 날짜 → 재고 0, 음수·통화 누락 → `rejected` |
| 단위 | `SupplierBNormalizer` | `totalPrice` 그대로, `priceDetail == null`, `taxIncluded=false` → `rejected` |
| 어댑터 (`ExchangeFunction` 스텁) | `SupplierAAdapter` | 200 → Success · 503/500 → `SERVER_ERROR` · 401 · 400 · 429 · 알 수 없는 4xx → `UNEXPECTED` · 깨진 JSON → `UNEXPECTED` · **51개 → 요청 없이 `BAD_REQUEST`**(스텁이 호출되지 않음을 확인) · 나간 요청의 `X-Api-Key`와 쿼리 파라미터 |
| 어댑터 (`ExchangeFunction` 스텁) | `SupplierBAdapter` | **200 + `E503` → `SERVER_ERROR`** · 200 + `E401` · `E400` · 모르는 코드 → `UNEXPECTED`(`E5xx`/`E4xx`는 관대 분류) · `data: null` 처리 · 200 + `0000` → Success |
| 통합 (실제 Mock 모듈) | 어댑터 A·B + 카탈로그 동기화 | `:mock-supplier`를 같은 JVM에 두 번째 컨텍스트로 띄우고 → `catalog sync` → **DB에 숙소 3·객실 6이 실제로 생긴다.** Mock `error` 모드 → 해당 공급사 FAILED, 다른 쪽 SUCCESS. **`no-response` 모드 → `TIMEOUT`, 아무도 안 듣는 포트 → `CONNECTION_FAILED`** |

**분류 매트릭스는 MockWebServer가 아니라 Spring 자체의 `ExchangeFunction` 스텁으로 검증한다** (2026-09-05 결정).
`ClientResponse.create(status).header(...).body(json).build()`로 응답을 만들어 `WebClient.builder().exchangeFunction(stub)`에 끼우면
네트워크 없이 JVM 안에서 상태 코드·본문 조합을 전부 만들 수 있다. 429·깨진 JSON·모르는 코드는 우리 Mock 모듈이 만들지 않으므로
(설계상 `429`·`500` 모드 없음) 임의 응답이 필요한데, 그걸 Spring이 이미 제공한다.

| 검토한 선택지 | 버린 이유 |
|---|---|
| okhttp `mockwebserver` | Boot 4 BOM이 관리하지 않아 버전을 직접 고정해야 하고, 최신 5.x는 alpha다. 안정 4.12.0은 okhttp·Kotlin stdlib을 테스트 클래스패스에 끌고 온다. 같은 검증을 Spring이 주는 도구로 할 수 있으므로 규칙 12(런타임 동작 근거로만 채택)를 통과하지 못한다 |
| WireMock | 4.x는 beta, 3.x는 이 용도에 과하다 |

스텁이 못 하는 것은 **실제 타임아웃과 연결 거부**다. 이 둘은 Mock 모듈 통합 테스트가 실제 와이어로 검증한다 —
`no-response` 모드가 `TIMEOUT`을, 닫힌 포트가 `CONNECTION_FAILED`를 만든다. 정상·장애 경로도 통합 테스트가 실제 HTTP로 한 번 더 지나가므로,
스텁이 HTTP 인코딩을 안 탄다는 공백은 그쪽이 메운다.

**통합 테스트의 Mock 기동 방식** — `testImplementation(project(":mock-supplier"))`로 클래스패스에 넣고,
테스트에서 `SpringApplicationBuilder(MockSupplierApplication.class)`로 **두 번째 컨텍스트**를 띄운다
(설계는 `.properties("server.port=0")`였으나 구현은 `.run("--server.port=0", …)` 커맨드라인 인자 — 이유는 §14·§15). 두 앱의 base package가 다르고(`com.demo.stayintegration` / `com.demo.mocksupplier`) 컴포넌트 스캔이 겹치지 않으므로
한 JVM에 공존한다. 별도 프로세스가 아니라는 점은 "자기 자신 호출" 문제와 무관하다 — 포트가 다르고 톰캣 인스턴스가 다르다.
이 방식이 6단계(견고성 통합 테스트)의 기반이 된다.

---

## 11. 이 설계로 갱신이 필요한 문서

| 문서 | 무엇 |
|---|---|
| `CLAUDE.md` 실패 분류 표 | 3분류 → `FailureKind` 8값 + `retryable()`. 관측성 `outcome` 태그는 kind 이름에서 파생 |
| `CLAUDE.md` Architecture | `supplier.normalization`, `supplier.adapter.support` 추가 |
| `.claude/rules/adapter.md` | `AvailabilityResult`(항목 단위 격리), `exchangeToMono`, 타임아웃 두 겹, 50개 사전 거절, 서킷 자리 |
| `docs/01-requirements-analysis.md` §4.5 | "날짜별 내역 합계가 총액과 일치하는지 검증" — **A에 총액 필드가 없어 성립하지 않음.** 삭제 |
| `docs/02-tech-stack.md` §2 | 서킷 적용 지점을 "어댑터 안"으로(`transformDeferred(CircuitBreakerOperator.of(cb))`) |

---

## 12. 검토했다 버린 대안

| 대안 | 버린 이유 |
|---|---|
| `retrieve()` + `onStatus()` | 4xx/5xx가 예외가 되어 에러 본문을 detail에 담기 번거롭고, B에는 아무 소용이 없다 |
| 항목 하나 결함 → 공급사 전체 `Failure` | 상품 하나의 음수 요금이 그 공급사 상품 전부를 지운다. 부분 실패 허용의 정신에 어긋난다 |
| `FailureKind` 3개 유지 + 별도 원인 enum | 두 enum을 항상 짝으로 들고 다녀야 한다. 원인 하나에 `retryable()`이 단순하다 |
| 모르는 에러 코드를 재시도 가능으로 | 재시도 예산이 데드라인 안에 갇혀 있어, 이해 못 하는 실패에 예산을 쓰면 이해하는 실패를 재시도할 기회를 잃는다 |
| 어댑터 하나가 두 공급사 처리(전략 패턴) | 공급사가 다르면 DTO·판정·정규화가 전부 다르다. 공유할 게 파이프라인 골격뿐인데 그건 `support`로 뺀다 |
| 통합 테스트에서 Mock을 별도 프로세스로 | Gradle 태스크 의존과 프로세스 관리가 복잡하다. 같은 JVM 두 컨텍스트로 충분하다 |
| MockWebServer / WireMock | §10 — Spring `ExchangeFunction` 스텁이 같은 일을 의존성 없이 한다. 최신 버전이 alpha/beta이고 BOM 밖이다 |
| `WebClient.builder()` 직접 생성 | §6.1 — `starter-webclient`가 Jackson 3 코덱과 Micrometer 계측을 함께 준다 |
| `WebClient` 하나를 공급사가 공유 | 타임아웃이 공급사 단위 설정(D-7)이므로 클라이언트도 공급사 단위여야 한다 |

---

## 13. 남는 문제 (설계로 남긴다)

- **재시도** — `retryable()`이 true인 실패에 `retryWhen(backoff)`. 총 예산 = 응답 타임아웃 × 시도 수가 데드라인(3s)을 넘지 않게
  시도 수를 1로 제한해야 하는데, 그러면 2s + 2s = 4s > 3s라 **응답 타임아웃을 줄이지 않으면 재시도 자체가 불가능**하다. 이 산술이
  재시도를 "버리는 순서 1위"에 둔 이유다.
- **통화** — `Price.currency`를 그대로 담는다. 환산·혼합 노출은 정책 문제라 다루지 않는다.
- **카탈로그 항목 결함 격리** — `AvailabilityResult`처럼 `rejected`를 두지 않았다. 필요해지면 `SyncReport`에 자리를 만든다.
- **응답 크기** — 50개 숙소 × 객실 타입 × 30박이면 항목당 30개 `NightlyRate`. `PriceDetail`을 항상 채우는 것이 비용이 되는 시점에는
  요청 파라미터로 `priceDetail` 포함 여부를 고르게 하는 것을 검토한다.

---

## 14. 구현하면서 달라진 것 (2026-09-05)

| 설계 | 구현 | 이유 |
|---|---|---|
| `ExceptionClassifier` | **`FailureClassifier`** — `fromException()` + `fromStatus()` | A의 HTTP 상태 판정과 B의 비-2xx 방어가 같은 표를 쓴다. 상태 분류까지 하는 클래스에 "Exception"이라는 이름은 거짓이다(규칙 14) |
| 골격 분리는 7단계에서 결정 | **`SupplierCallPipeline`을 지금 뺐다** (공급사 단위 인스턴스) | A·B 어댑터 × 두 API = 같은 15줄이 네 번 반복될 참이었다. 서킷이 들어갈 자리도 이 클래스 한 곳으로 정해진다 |
| `.map(body -> normalize → Success(elapsed))` | **`CallOutcome<T>`**(Ok / Failed) 를 파이프라인이 `SupplierResult`로 바꾼다 | 상태 코드·`resultCode`로 실패를 판정하는 시점에는 elapsed를 아직 모른다. 판정 결과와 소요시간을 분리해야 파이프라인이 마지막에 한 번 찍을 수 있다 |
| 항목 결함 → `rejected` (방법 미정) | normalizer 안에서 **`ItemDefect`**(스택 트레이스 없는 예외)를 던지고 항목 루프가 잡아 `NormalizationIssue`로 | 검사 8가지가 있는 항목 변환을 값 반환으로 쓰면 모든 검사가 `if … return issue`로 늘어진다. 예외는 normalizer 밖으로 나가지 않으므로 "실패는 값으로" 계약과 충돌하지 않는다 |
| DTO 날짜 `LocalDate` (암묵) | **`String`으로 받고 normalizer가 파싱**. 금액은 **boxed `Long`** | `LocalDate`면 항목 하나의 날짜 오류가 본문 전체 파싱 실패(`Failure`)가 된다. primitive `long`이면 누락이 0원으로 둔갑한다 |
| (미정) A `dailyRates`가 비어 있을 때 | **`rejected`** — "dailyRates missing" | A의 요금은 날짜별 단가에서만 만들어진다. 하루치도 없으면 총액을 지어낼 수 없다. 일부만 빠진 경우는 설계대로 `availableRooms=0`으로 살린다 |
| (미정) 2xx인데 본문이 비어 있을 때 | 파이프라인이 `switchIfEmpty`로 **`Failure(UNEXPECTED, "empty response")`** | `exchangeToMono`가 빈 Mono를 내면 결과 자체가 사라져 병합에서 그 공급사가 증발한다 |
| `items` 필드 없는 응답 | **`Failure(UNEXPECTED)`**, 빈 목록이 아님 | 빈 카탈로그로 읽으면 동기화가 그 공급사 매핑을 전부 비활성화한다 |
| 어댑터 `@Component` (stub 테스트 영향 미검토) | `SupplierRegistry`에 **`List<SupplierAdapter>` 생성자** 추가, `CatalogSyncServiceTest`가 `@Primary`로 stub만 담은 레지스트리를 끼운다 | 실제 어댑터가 빈이 되면서 stub 테스트의 레지스트리에 4개가 들어갔다. 실제 어댑터를 빼는 다른 방법(프로파일·조건부 빈)은 운영 코드에 테스트 사정을 심는다 |
| Mock을 `SpringApplicationBuilder.properties()`로 설정 | **커맨드라인 인자**(`run("--server.port=0", …)`) + `DataSourceAutoConfiguration` 제외 | `properties()`는 기본값(최저 우선순위)이라 클래스패스의 `application.yaml`(`server.port: 8080`)에 진다. 두 모듈의 yaml이 같은 이름으로 클래스패스에 함께 있어 Mock 컨텍스트가 **본체 yaml을 읽는다** — Mock 설정은 인자로 명시. Mock 컨텍스트가 본체의 JPA 클래스패스를 보므로 DataSource 자동설정 하나만 빼면 나머지(Hibernate·JPA 리포지토리)는 DataSource 빈 조건으로 스스로 빠진다 |
| `starter-webflux-test` | **`starter-webclient-test`** | BOM 4.1.1에 존재 확인 |
| — | Java 25 + Netty 네이티브 로딩 경고 → 테스트·bootRun JVM 인자 `--enable-native-access=ALL-UNNAMED` | 내 diff가 만든 경고는 커밋 전에 지운다(규칙 10) |

### 테스트 (실제 수)

| 층 | 클래스 | 수 |
|---|---|---|
| 단위 | `InventoryRuleTest` · `FailureClassifierTest` · `SupplierPropertiesTest` | 6 · 6 · 3 (→ 07에서 `Circuit` 검증이 더해져 4) |
| 단위 | `SupplierANormalizerTest` · `SupplierBNormalizerTest` | 9 · 8 |
| 어댑터 (`ExchangeFunction` 스텁 `StubExchange`) | `SupplierAAdapterTest` · `SupplierBAdapterTest` | 13 · 11 |
| 실제 와이어 (Mock 두 번째 컨텍스트, Spring 컨텍스트 없음) | `SupplierAdapterWireTest` — 정상 A·B·카탈로그, A 503, B 200+E503, **no-response → TIMEOUT**, delay → TIMEOUT, **닫힌 포트 → CONNECTION_FAILED**, 잘못된 키 → 두 방식 모두 UNAUTHORIZED | 9 |
| 전 구간 (Boot 컨텍스트 + Testcontainers + Mock) | `CatalogSyncWireTest` — **DB에 숙소 3·객실 6이 실제로 생긴다**, B 장애 → b FAILED/a SUCCESS, A 무응답 → TIMEOUT이고 b는 기다리지 않는다 | 3 |

스텁 테스트는 `SupplierWebClients`를 우회하지 않는다 — `WebClient.builder().exchangeFunction(stub)`을 Builder로 넘기면 `exchangeFunction`이 커넥터보다 우선하므로,
`baseUrl`·`X-Api-Key`·쿼리 파라미터가 붙는 것까지 운영과 같은 코드로 검증된다.

`CatalogSyncWireTest`의 컨텍스트가 뜬다는 것 자체가 §6.1의 증명이다 — `starter-webclient` 없이는 `SupplierWebClients`가 `WebClient.Builder`를 주입받지 못한다.

이후 단계에서 이 패키지에 더해진 테스트: `SupplierCircuitBreakerTest` 6(07 — 실제 어댑터 + `StubExchange`로 서킷).

---

## 15. 구현 기록 — 막힌 지점과 요약 (2026-09-05)

### 막힌 지점

**1. Mock 두 번째 컨텍스트가 8080을 물려고 했다.**
`SpringApplicationBuilder.properties("server.port=0")`로 줬는데 `Port 8080 is already in use`로 죽었다. `properties()`는 **기본값
(최저 우선순위)** 이라 클래스패스에서 잡힌 `application.yaml`의 `server.port: 8080`이 이긴다. 그런데 Mock 컨텍스트가 읽은 yaml은
Mock 것이 아니라 **본체 것**이었다 — 두 모듈의 yaml이 같은 이름으로 테스트 클래스패스에 함께 있고, `classpath:application.yaml`은
먼저 보이는 하나만 잡는다(본체의 `build/resources`가 의존 jar보다 앞). 해결: 설정을 **커맨드라인 인자**(`run("--server.port=0", …)`)로
넘겨 어떤 yaml이 잡히든 이기게 하고, Mock의 `mock.api-key.*`·`delay-default-ms`도 인자로 명시했다.
교훈: 같은 JVM에 두 Boot 앱을 띄우면 **설정 파일 이름 충돌**을 먼저 의심한다.

**2. Mock 컨텍스트가 DataSource를 만들려 했다.**
Mock은 DB가 없는데 본체의 테스트 클래스패스(JPA 스타터·PostgreSQL 드라이버)를 그대로 보므로 `DataSourceAutoConfiguration`이 접속 정보를
찾다 실패한다. 자동설정 제외 목록을 길게 쓰는 대신 **이것 하나만** 뺐다 — `HibernateJpaConfiguration`은 `@ConditionalOnSingleCandidate(DataSource)`,
`JpaRepositoriesAutoConfiguration`은 `@ConditionalOnBean(DataSource)`라 DataSource 빈이 없으면 스스로 빠진다. 클래스 이름은 문자열이 아니라
`DataSourceAutoConfiguration.class.getName()`으로 — Boot 4에서 패키지가 또 옮겨도 컴파일 에러로 드러난다.

**3. `SupplierRegistry`에 생성자가 둘이 되자 컨텍스트가 뜨지 않았다.**
stub 테스트용 `List` 생성자를 추가했더니 Spring이 "No default constructor found" — 생성자가 둘이면 어느 것을 쓸지 `@Autowired`로 지정해야 한다.
단위·스텁 테스트는 전부 통과한 뒤 전 구간 테스트에서만 드러났다. 컨텍스트를 띄우는 테스트가 없으면 이런 건 배포 때 만난다.

**4. Java 25 + Netty.** 테스트 출력에 `A restricted method in java.lang.System has been called` 경고가 새로 생겼다(Netty 네이티브 로딩).
내 diff가 만든 경고라 테스트·`bootRun` JVM 인자에 `--enable-native-access=ALL-UNNAMED`를 명시했다.

### 그 외 요약

- 설계와 달라진 결정은 §14 표에 있다. 핵심은 셋 — 호출 골격 `SupplierCallPipeline`을 지금 뺀 것, 판정 결과를 `CallOutcome`으로 나르고 elapsed는 파이프라인이
  마지막에 찍는 것, 항목 결함을 `ItemDefect`로 끊어 `rejected`에 담는 것.
- 스텁 테스트가 `SupplierWebClients`를 우회하지 않게 `WebClient.builder().exchangeFunction(stub)`을 Builder로 넘겼다 — `exchangeFunction`이
  커넥터보다 우선한다는 Spring의 동작에 기댄다(`DefaultWebClientBuilder.initExchangeFunction`).
- 실제 어댑터가 빈으로 들어오면서 `CatalogSyncServiceTest`의 레지스트리에 4개가 잡혔다. 운영 코드에 프로파일·조건부 빈을 심지 않고,
  테스트가 `@Primary`로 stub만 담은 `SupplierRegistry`를 끼우는 쪽을 택했다.
- Mock 모드 전환은 HTTP 제어 API가 아니라 Mock 컨텍스트의 `MockModeRegistry` 빈을 직접 만진다 — 같은 코드 경로, 왕복 없음.
- 검증: `./gradlew build` 통과, 테스트 118개(app 88 · mock 30). 와이어 테스트 12개가 실제 HTTP로 타임아웃·연결 거부·200+E503을 지나갔다.

---

## 미구현 항목

| 항목 | 이유 |
|---|---|
| 재시도(`retryWhen`) | 응답 타임아웃 2s × 2회 = 4s > 데드라인 3s. 응답 타임아웃을 줄이지 않으면 재시도 예산이 없다(§13). `FailureKind.retryable()`은 그 자리를 위해 남겨 뒀고, 지금은 서킷의 기록 기준으로 쓰인다 |
| 카탈로그 항목 결함 격리(`rejected`) | 발생 빈도가 낮고 `SyncReport`에 담을 자리가 없다. 코드가 빈 항목은 건너뛰고 `warn`으로만 남긴다(§5.6). 필요해지면 리포트에 자리를 만든다 |
| 통화 환산·ISO 4217 형식 검증 | 환산은 정책 문제. 형식은 "비어 있지 않음"만 검사한다 — 현재 데이터가 KRW 하나라 검증할 대상이 없다 |
| `priceDetail` 포함 여부 파라미터 | 50숙소 × 30박이면 항목당 `NightlyRate` 30개다. 응답 크기가 비용이 되는 시점의 일이고, 지금 규모(≤300숙소·3박)에서는 문제가 드러나지 않았다(§13) |
| A·B 외 공급사 | 스펙에 있는 공급사가 둘이다. "신규 공급사 추가 시 고칠 것"은 세 번째를 만들어 보는 대신 ArchUnit 경계와 레지스트리·팩토리 구조로 증명한다(§7) |
| MockWebServer·WireMock | Spring `ExchangeFunction` 스텁이 같은 일을 의존성 없이 한다(§10). 실제 타임아웃·연결 거부는 Mock 컨텍스트가 와이어로 |
