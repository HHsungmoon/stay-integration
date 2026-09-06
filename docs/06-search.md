# 통합 검색 API — 설계

작성일: 2026-09-05
상태: 구현 완료 (2026-09-05). §14·§15는 구현 후 추가

`search` 패키지. 카탈로그에서 "물어볼 숙소"를 읽고, 공급사마다 청크로 나눠 병렬 호출하고, 결과를 하나의 응답으로 합친다.
핵심 흐름(Mock → 카탈로그 → 어댑터 → **통합 검색 → 부분 실패**)의 마지막 두 마디가 이 단계다.

## 요약 — 검색은 무엇을 하는가

고객은 날짜와 인원만 준다. 공급사는 지역 검색을 해 주지 않으므로 어떤 숙소를 물어볼지는 우리가 알고 있어야 하고, 그 목록이 카탈로그다.
검색은 ① 카탈로그를 **한 번에** 읽어 in-memory `CatalogLookup`을 만들고 ② 공급사별 숙소 코드를 50개씩 잘라 ③ 어댑터를 병렬로 부르고
④ 돌아온 `SupplierResult`들을 합쳐 ⑤ 공급사 코드를 내부 식별자로 바꿔 응답한다.

가장 중요한 것 둘. **부분 실패** — 한 공급사가 죽어도 나머지로 응답하고, 어느 공급사가 왜 실패했는지 응답에 드러낸다(`status: PARTIAL`).
**스레드 모델** — 리액티브 체인 안에서 JPA를 부르지 않는다. 매핑은 체인 시작 전에 읽고, `.block()`은 컨트롤러에서 한 번이다.

---

## 0. 이 단계의 범위

| 만드는 것 | 패키지 |
|---|---|
| `SearchRequest`(검증 D-13) | `search.dto.request` |
| `SearchResponse`·`SupplierOutcome`·`StayItem`·`PriceResponse`·`PriceDetailResponse`·`NightlyRateResponse` | `search.dto.response` |
| `StaySearchController` — 계약 경로, `.block()` 1회, `Cache-Control` | `search.controller` |
| `StaySearchService` — 진입점: lookup 읽기 → fetcher → assembler | `search.service` |
| `SupplierAvailabilityFetcher` — 청크 분할·동시성 상한·공급사별 데드라인 | `search.service` |
| `SearchResultAssembler` — 병합·내부 ID 변환·D-10·D-11·상태 판정 (순수 함수) | `search.service` |
| `SearchProperties` — `chunk-size`·`concurrency-per-supplier`·`deadline` | `search` 루트 |
| `GlobalExceptionHandler` 400 처리 확장 | `common` |
| `ArchitectureTest` — 경계 5개 (07·08에서 각 1개씩 더해져 지금은 7) | `test` 루트 |

**선행 refactor 2개** (기능 커밋 앞에 분리, 규칙 8)

1. **`CatalogLookupLoader`(service) → `catalog.function.CatalogLookupReader`.** search 서비스가 catalog **서비스**를 주입하면 서비스 간 의존이
   생긴다 — 규칙은 function을 쓰라고 한다. 이 클래스는 엔티티 → `CatalogLookup` 조립이라 function의 정의("조립·변환")에 정확히 맞다.
   `@Transactional`은 벗긴다(function은 트랜잭션을 열지 않는다. 조회는 JOIN FETCH 한 번이라 트랜잭션이 필요 없다). `CatalogLookupLoaderTest`도 따라간다.
2. **ArchUnit `ArchitectureTest`.** 어댑터 패키지가 생긴 지금이 경계 1·2·3을 실제로 검증할 수 있는 첫 시점이다. search를 만들기 **전에** 두면
   search 코드가 경계를 넘는 순간 빌드가 알려준다(§9).

**하지 않는 것**: 재시도(버리는 순서 1위), 서킷(7단계 — `Skipped` 병합 코드는 지금 들어간다), 관측성 지표(8단계 — 집계 지점만 한 곳으로 모아 둔다),
정렬·필터·페이징(범위 밖), 통화 환산.

---

## 1. API 계약

```
GET /api/v1/stays/search?checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0
```

### 1.1 검증 (D-13) → 400

| 규칙 | `code` | 처리 위치 (구현) |
|---|---|---|
| 파라미터 누락 | `MISSING_PARAMETER` | `@RequestParam` 누락 → `MissingServletRequestParameterException` → handler |
| 날짜 형식·정수 형식 오류 | `INVALID_PARAMETER` | `MethodArgumentTypeMismatchException` → handler |
| `adults < 1`, `children < 0` | `INVALID_PARAMETER` | `SearchRequest` **생성자** → `InvalidSearchRequestException(code)` |
| `checkOut <= checkIn` | `INVALID_DATE_RANGE` | 동일 |
| 30박 초과 | `TOO_MANY_NIGHTS` | 동일 |

**구현은 컨트롤러가 `@RequestParam` 넷을 받아 `new SearchRequest(...)`를 만드는 형태다.** 검증은 전부 record 생성자에 있어 "만들 수 있으면 유효한 요청"이고,
Bean Validation은 쓰지 않는다. 설계 시점에는 `@ModelAttribute` record + `validate()` + `@Min`이었으나 record 바인딩은 누락과 형식 오류가 필드 오류 하나로
뭉개져 위 표의 첫 두 code를 나눌 수 없어 바꿨다(§14). 과거 체크인은 거부하지 않는다(D-12). 에러 응답은 기존 `ErrorResponse(code, message)`.

### 1.2 응답 (HTTP 200 — 전 공급사 실패도 200)

```json
{
  "status": "PARTIAL",
  "checkIn": "2026-09-01", "checkOut": "2026-09-04", "nights": 3, "adults": 2, "children": 0,
  "suppliers": [
    { "supplier": "a", "status": "SUCCESS", "calls": 1, "failedCalls": 0, "skippedCalls": 0, "elapsedMs": 84,
      "offers": 4, "rejected": 0, "unmapped": 0, "failure": null },
    { "supplier": "b", "status": "FAILED",  "calls": 1, "failedCalls": 1, "skippedCalls": 0, "elapsedMs": 61,
      "offers": 0, "rejected": 0, "unmapped": 0,
      "failure": { "kind": "SERVER_ERROR", "retryable": true, "detail": "E503 TEMPORARILY_UNAVAILABLE" } }
  ],
  "items": [
    { "propertyId": 1, "propertyName": "Riverside Hotel Seoul",
      "roomTypeId": 1, "roomTypeName": "Deluxe Twin", "maxOccupancy": 2,
      "availableRooms": 1, "available": true, "supplier": "a",
      "price": { "currency": "KRW", "totalAmount": 429000, "taxIncluded": true, "breakfastIncluded": false, "nights": 3 },
      "priceDetail": { "taxAmount": 39000, "nightlyBreakdown": [ { "date": "2026-09-01", "net": 120000, "tax": 12000, "remainingRooms": 3 }, "…" ] } },
    { "propertyId": 2, "roomTypeId": 4, "roomTypeName": "Standard Double", "availableRooms": 0, "available": false, "…": "…" }
  ]
}
```

- **envelope 없음.** `status`가 곧 판정이다(컨트롤러 규칙). `status != OK` **또는 items가 비면** `Cache-Control: no-store`(§3).
- `items`는 **(공급사, 숙소, 객실 타입) 한 줄씩 flat.** 같은 호텔이 A·B에 있어도 공통 키가 없어 내부 숙소가 둘이므로, 숙소로 묶어도 합쳐지지 않는다.
  묶기는 응답 구조만 깊어지고 클라이언트가 어차피 펼친다.
- 요금은 포트 record(`Price`·`PriceDetail`)를 그대로 내보내지 않고 `PriceResponse`로 옮긴다. 포트 타입이 API 계약에 묶이면 포트를 고칠 때 클라이언트가 깨진다
  (경계 5의 정신 — 엔티티뿐 아니라 내부 모델도 컨트롤러 경계를 그대로 넘지 않는다).
- `failure.retryable`을 노출하는 이유: 클라이언트가 "잠시 후 재시도" 안내를 낼 수 있는 유일한 근거다. `kind` 문자열 매칭을 시키는 것보다 낫다.
- 정렬은 범위 밖이지만 **출력은 결정적**이어야 테스트가 된다: `propertyId → roomTypeId → supplier`.

---

## 2. 흐름

```
Controller.search(checkIn, checkOut, adults, children)        톰캣 워커
  ├ new SearchRequest(...)                                      D-13 검증 → 400 (생성자)
  └ staySearchService.search(request).block()                   ← 유일한 block. cacheable()이 아니면 no-store

StaySearchService.search(request) : Mono<SearchResponse>
  ├ lookup = catalogLookupReader.load()            JPA — 체인 시작 전, 한 번
  └ fetcher.fetchAll(request, lookup)              Mono<List<SupplierFetchOutcome>>
        .map(outcomes -> { response = assembler.assemble(request, lookup, outcomes);
                           supplierCallMetrics.record(...)   ← 08. 병합 지점에서 지표
                           return response; })

SupplierAvailabilityFetcher.fetchAll
  Flux.fromIterable(registry.all())
      .flatMap(adapter -> fetchSupplier(adapter, request, lookup.hotelCodesOf(adapter.id())))
      .collectList()

  fetchSupplier(adapter, request, codes)
    codes 비어 있음 → SupplierFetchOutcome(id, [Skipped("no mapped properties")], 0ms)      호출 없음
    chunks = partition(codes, chunkSize)
    Mono.defer(() -> {  started = nanoTime
      Flux.fromIterable(chunks)
          .flatMap(chunk -> adapter.fetchAvailability(request.toQuery(chunk))
                              .onErrorResume(e -> UNEXPECTED),        청크 단위 안전망 — 이 청크만 잃는다
                   concurrencyPerSupplier)
          .take(deadline)                                              ← §4 B: 도착한 청크는 살린다
          .collectList()
          .map(arrived -> 안 온 청크 수만큼 Failure(TIMEOUT) 채움)       청크 수 = 결과 수
          .onErrorResume(e -> [UNEXPECTED])                             공급사 단위 안전망(동기 예외)
          .map(results -> new SupplierFetchOutcome(id, results, elapsed(started)))
    })
```

`SupplierFetchOutcome(SupplierId, List<SupplierResult<AvailabilityResult>>, Duration elapsed)` — 공급사 하나의 **청크 결과 묶음**과 그 공급사를 기다린 벽시계 시간.
어댑터 결과를 바꾸지 않고 담기만 한다. 병합·판정은 assembler 한 곳에서 하고, 지표는 그 직후 service의 `map` 안에서 기록한다(08 §3).

---

## 3. 상태 판정

공급사 단위(`SupplierOutcome.status`):

| 청크 결과 | 상태 |
|---|---|
| 전부 `Success` | `SUCCESS` |
| `Success`와 `Failure` 혼재 | `PARTIAL` — 청크 단위 부분 실패 허용(D-9) |
| 전부 `Failure` | `FAILED` |
| 호출하지 않음(`Skipped`, 코드 없음) | `SKIPPED` |

`failure`에는 **첫 실패**의 kind·detail을 담는다. 청크가 여럿이면 원인이 다를 수 있지만 응답에 배열을 두는 것은 과하다 — `failedCalls`가 개수를 말한다.

전체(`status`):

| 조건 | 상태 |
|---|---|
| 실패한 청크가 하나도 없다 | `OK` |
| 실패도 있고 성공도 있다(공급사 간이든 청크 간이든) | `PARTIAL` |
| 호출한 청크가 전부 실패 | `ALL_FAILED` |

`SKIPPED`는 어느 쪽에도 세지 않는다 — 서킷이 열려 부르지 않은 것은 "실패"가 아니라 "보호 동작"이고, 코드가 없어 부르지 않은 것은 카탈로그의 상태다.
다만 개수는 `skippedCalls`에 남긴다(07 §5 — 요청 도중 서킷이 열리면 앞 청크는 `calls`에, 뒤 청크는 여기에 남아 합이 계획한 청크 수가 된다).
**전 공급사가 `SKIPPED`면 `OK` + 빈 `items`**(카탈로그가 비었거나 전부 서킷 열림). 이 경우도 `no-store`를 붙인다 — 빈 결과가 캐시되면 안 된다.
→ `Cache-Control: no-store` 조건은 "`status != OK` **또는** `items`가 비어 있음"으로 둔다.

---

## 4. 청크 분할·동시성·데드라인 (D-9)

```yaml
search:
  chunk-size: 50                 # 공급사 계약 상한. 어댑터가 51개를 거절하므로 이 값이 틀리면 테스트가 바로 잡는다
  concurrency-per-supplier: 4    # 한 공급사에 동시에 열 연결 수. 응답 2s × 4 = 한 파동에 200개 숙소
  deadline: 3s                   # D-7 오케스트레이션. 검색 전체가 이 안에 끝나야 한다
```

동시성 상한이 **공급사 단위**인 이유: 상한이 없으면 숙소 1,000개 = 20청크가 한 공급사에 동시에 꽂힌다. 우리가 그 공급사의 429를 유발하는 셈이다.
4는 근거 있는 숫자가 아니라 **출발점**이다 — 공급사 계약에 동시 호출 상한이 없으니, 파동 하나(4 × 50 = 200 숙소)가 2초 안에 끝나는 규모를 기준으로 잡고
yaml로 뺐다. README의 "숙소가 수천 개로 늘 때"에서 이 숫자와 데드라인의 산술을 다룬다.

**데드라인 초과 시 완료분만으로 응답하는 것 — 결정: B(2026-09-05)**

| 선택 | 동작 | 비용 |
|---|---|---|
| A. D-9 그대로(설계로만) | `.timeout(deadline)` fallback이 그 공급사를 `TIMEOUT` **1건**으로 바꾼다. 먼저 끝난 청크도 버려진다 | 0 |
| B. 완료분 유지 | `Flux.merge(chunks).take(deadline)`로 도착한 것만 모으고, 안 온 청크 수만큼 `TIMEOUT`을 채운다 | 코드 ~10줄, 테스트 1개 |

**→ B로 확정(2026-09-05).** CLAUDE.md D-9를 같은 커밋에서 "구현"으로 고쳤다. 구현은 `flatMap(…, concurrency).take(deadline).collectList()` 뒤 `chunks.size() - arrived.size()`만큼 `TIMEOUT`을 채운다.

설계 시점에는 카탈로그가 3숙소라 청크가 1개였고 두 선택의 동작이 같았다. B를 고른 근거는 D-9의 "청크 단위 부분 실패 허용"을 데드라인에도 일관되게
적용하는 것이고 비용이 작다는 점. 두 선택이 실제로 갈리는 규모(6청크·2파동)는 2026-09-06 `SearchScaleWireTest`로 처음 돌렸다(§13).

---

## 5. 응답 조립 — `SearchResultAssembler` (순수 함수)

입력: `SearchRequest`, `CatalogLookup`, `List<SupplierFetchOutcome>`. 출력: `SearchResponse`. I/O 없음, 단위 테스트가 전부 여기 붙는다.

각 `Success`의 `offers`마다:

1. `lookup.find(supplier, hotelCode, roomTypeCode)` — **없으면 미매핑(D-10)**: 항목 제외, `warn` 로그, `SupplierOutcome.unmapped++`. 응답 전체는 살린다.
   `unmapped > 0`이면 동기화가 밀렸다는 신호다 — 관리 엔드포인트로 동기화를 다시 돌리면 사라진다.
2. 이름·`maxOccupancy`는 **Offer(② 값)**를 쓴다(D-11). lookup 값과 다르면 `info` 로그(숙소명 변경 감지) — 응답은 ② 값.
3. `available = availableRooms > 0`(D-6). 0이어도 빼지 않는다.
4. `Price` → `PriceResponse`, `PriceDetail` → `PriceDetailResponse`(null이면 null 그대로 — `nullable`이 계약이다).

`rejected`(어댑터의 항목 격리)는 개수만 `SupplierOutcome.rejected`에 담는다. 사유는 어댑터가 이미 `warn`으로 남길 자리가 없으므로 **assembler가 로그로 남긴다** —
어댑터는 순수하게 두고 로그는 병합 지점 한 곳에서.

---

## 6. 패키지

```
search/
├── SearchProperties                          chunk-size · concurrency-per-supplier · deadline
├── controller/StaySearchController
├── service/StaySearchService                 진입점. lookup 읽기(체인 밖) → fetcher → assembler
├── service/SupplierAvailabilityFetcher       청크·동시성·데드라인. 결과를 바꾸지 않는다
├── service/SearchResultAssembler             병합·ID 변환·D-10·D-11·상태. 순수 함수 (지표는 08에서 service의 map에 — assembler는 레지스트리를 모른다)
├── service/SupplierFetchOutcome              fetcher → assembler 전달 record (+ elapsed)
├── dto/request/SearchRequest                 생성자 검증
├── dto/request/InvalidSearchRequestException 설계는 service/였으나 SearchRequest가 던지므로 여기(§14)
└── dto/response/SearchResponse · SupplierOutcome · StayItem · PriceResponse · PriceDetailResponse · NightlyRateResponse
```

`function`이 없는 이유: search는 자기 repository가 없다. 카탈로그 읽기는 `catalog.function.CatalogLookupReader`(선행 refactor 1)를 쓴다 —
**feature 간 데이터 공유는 function으로**라는 규칙의 첫 실사용이다.

`StaySearchService`는 `@Transactional(readOnly = true)`를 **클래스에 두지 않는다.** `search()`가 반환하는 `Mono`는 메서드가 끝난 뒤 컨트롤러에서 구독되므로
트랜잭션 안에서 공급사를 부르는 일은 없지만, 애노테이션이 있으면 읽는 사람이 "이 안에서 블로킹 I/O가 돈다"고 오해한다. lookup 읽기는 function이 하는
단일 쿼리라 트랜잭션이 필요 없다(catalog의 `CatalogSyncService`와 같은 판단).

---

## 7. 스레드 모델 — 이 단계에서 지켜야 할 세 곳

| 규칙 | 어디서 지키나 |
|---|---|
| 체인 안에 JPA 없음 | `StaySearchService.search()`가 `lookup`을 **먼저** 읽고 체인에 값으로 넘긴다. fetcher·assembler는 `catalog` 패키지를 모른다(assembler는 `CatalogLookup` record만 안다) |
| `.block()` 1회 | `StaySearchController`만. service는 `Mono`를 돌려준다 |
| 정규화·병합은 순수 함수 | assembler — 입력 3개, 출력 1개, 필드 없음 |

ArchUnit이 막지 못하는 것은 첫 줄이다(`search.service`가 체인 안에서 `CatalogLookupReader`를 부르는 것은 패키지 의존으로는 정상). 리뷰 항목.

---

## 8. 에러 처리 — `GlobalExceptionHandler` 확장

| 예외 | HTTP | `code` |
|---|---|---|
| `MissingServletRequestParameterException` | 400 | `MISSING_PARAMETER` |
| `MethodArgumentTypeMismatchException` | 400 | `INVALID_PARAMETER` |
| `InvalidSearchRequestException` | 400 | 예외가 든 code(`INVALID_DATE_RANGE` / `TOO_MANY_NIGHTS`) |
| `SyncAlreadyRunningException` (기존) | 409 | 유지 |

Boot 4의 `ProblemDetail`(RFC 9457) 전환은 하지 않는다 — 기존 `ErrorResponse` 형태가 있고, 형태를 바꾸는 것은 기능이 아니다.
설계에 있던 `MethodArgumentNotValidException`(`@Min`) 행은 Bean Validation을 쓰지 않아 발생하지 않으므로 뺐다(§14).

---

## 9. ArchUnit — `ArchitectureTest`

| 경계 | 규칙 |
|---|---|
| 1 | `..supplier.adapter.a..`의 `*Response`류(정확히는 `SupplierAResponse`·`SupplierBResponse`와 그 중첩)는 자기 패키지 밖에서 참조되지 않는다. 지금은 `final class`가 non-public이라 컴파일러가 이미 막지만, 누가 `public`으로 바꾸는 순간을 잡는다 |
| 2 | `..search..`·`..catalog..`는 `..supplier.adapter..`에 의존하지 않는다 |
| 3 | `..supplier..`는 `jakarta.persistence..`·`org.springframework.data..`·`..repository..`에 의존하지 않는다 |
| 4 | `controller → service → function → repository` 단방향 — 구현은 `layeredArchitecture().consideringOnlyDependenciesInLayers()`(§14). repository는 function에서만 |
| 5 | 구현은 "`..controller..`가 `@Entity` 클래스에 의존하지 않는다"(§14 — 시그니처보다 강하다) |
| (07) 6 | `..search..`·`..catalog..`는 `io.github.resilience4j..`를 모른다 |
| (08) 7 | Micrometer는 `..common..`과 `..supplier.adapter.support..`만 |

의존성: `com.tngtech.archunit:archunit-junit5:1.5.0`(Java 25 바이트코드). `@AnalyzeClasses(packages = "com.demo.stayintegration", importOptions = DoNotIncludeTests)`.

---

## 10. 테스트

| 층 | 대상 | 검증 |
|---|---|---|
| 단위 | `SearchRequest` | D-13 다섯 규칙, 과거 날짜 허용 |
| 단위 | `SupplierAvailabilityFetcher.partition` | 0·50·51·120개 → 청크 수 |
| 단위 | `SearchResultAssembler` | 상태 표 전부(§3) · 미매핑 제외+카운트(D-10) · ② 값 우선(D-11) · `available:false` 유지(D-6) · `priceDetail null` 유지 · 정렬 · 전부 SKIPPED → OK+빈 items |
| `@WebMvcTest` | `StaySearchController` | 400 다섯 케이스와 `code` · `Cache-Control: no-store`가 `PARTIAL`·`ALL_FAILED`·빈 결과에만 · 서비스는 `@MockitoBean` |
| 단위(Spring 없이, stub 어댑터) | `SupplierAvailabilityFetcher` | 데드라인 초과 공급사 → 도착분 유지 + TIMEOUT 보정, 다른 공급사는 산다 · 동시성 상한 · 어댑터가 예외를 던져도 그 청크/공급사만 UNEXPECTED · 코드 없는 공급사는 호출되지 않는다 (설계의 "컨텍스트 + stub 서비스 테스트"를 대체 — §14) |
| **실제 와이어** (Mock 두 번째 컨텍스트 + Testcontainers) | 전 구간 | 동기화 → 검색: (1) 정상 병합 — items 6, OK (2) A `no-response` → PARTIAL, B 결과 있음, 3초 안 (3) B `error`(200+E503) → PARTIAL, `failure.kind = SERVER_ERROR` (4) 둘 다 `error` → ALL_FAILED, HTTP 200, `no-store` (5) 검색 후 카탈로그 비활성화 → `unmapped` 카운트 |

마지막 줄이 CLAUDE.md의 "견고성 통합 테스트"(6단계) 첫 판이다. 이후 이 클래스(`StaySearchWireTest`)에 07 서킷 3개, 08 지표·Actuator 3개, springdoc 1개가 더해져 13개이고,
규모(청크·데드라인)는 별도 클래스 `SearchScaleWireTest` 2개(2026-09-06)가 맡는다. 와이어 테스트는 `MockSupplierServer`(05 §14)를 그대로 쓴다.

---

## 11. 검토했다 버린 대안

| 대안 | 버린 이유 |
|---|---|
| 응답을 숙소로 묶기(`properties[].rooms[]`) | 같은 호텔이 공급사마다 다른 내부 숙소라 묶어도 합쳐지지 않는다. 구조만 깊어진다 |
| 포트 record(`Price`)를 응답에 그대로 | 포트를 고치면 API가 깨진다. 내부 모델이 컨트롤러 경계를 넘지 않는다 |
| `SupplierResult`를 응답에 직렬화 | sealed라 Jackson 다형성 설정이 필요하고, 내부 모델이 계약이 된다(dto 규칙) |
| 성공 envelope | 이중 status(컨트롤러 규칙) |
| 전 공급사 실패 → HTTP 502/503 | "검색은 정상 동작했고 결과가 실패"다. 5xx는 우리 장애로 읽혀 클라이언트 재시도 정책을 오작동시킨다 |
| `SKIPPED`를 실패로 세기 | 서킷의 보호 동작이 지표상 장애로 보인다. 카탈로그가 빈 것도 장애가 아니다 |
| 컨트롤러 파라미터 4개 + `@Validated` | 교차 검증(체크아웃 > 체크인, 30박)이 컨트롤러 본문에 들어온다. record 하나가 계약을 한 곳에 모은다 |
| 데드라인을 `collectList()` 전체에 | 하나가 늦으면 먼저 온 공급사 결과까지 잃는다(catalog와 같은 판단) |
| search에 `function` 계층 | repository가 없다. 빈 계층은 소음이다 |
| `CatalogLookupLoader`를 search가 주입 | 서비스 → 서비스 의존. 규칙이 막는 바로 그 형태라 function으로 옮긴다 |

---

## 12. 갱신이 필요한 문서

| 문서 | 무엇 |
|---|---|
| `CLAUDE.md` D-9 | §4에서 B를 택하면 "완료분만 응답은 설계로" → 구현으로 |
| `CLAUDE.md` Rules 표 · `.claude/rules/function.md` | `CatalogLookupReader`가 function으로 이동한 것 |
| `.claude/rules/controller.md` | `SearchRequest` 검증 방식(record + `validate()`), 400 `code` 표, `no-store` 조건에 "빈 결과" 추가 |
| `.claude/rules/service.md` | fetcher/assembler 분리와 "지표는 assembler 한 곳" |
| `docs/04-catalog.md` | `CatalogLookupLoader` → function 이동 |

---

## 13. 남는 문제 (설계로 남긴다)

- **동시성 상한의 근거** — 4는 출발점이다. 공급사 계약에 상한이 생기면 그 값으로, 없으면 429 관측치로 조정한다. 8단계 지표(`supplier.call`의 `RATE_LIMITED` 비율)가 근거가 된다.
- **숙소 수천 개** — 2,000숙소 = 40청크 / 동시 4 = 10파동 × 최대 2s = 20s > 3s. 데드라인 안에 못 끝난다. 답은 (a) 상한을 올리거나 (b) 검색 조건(지역)으로
  후보를 줄이거나 (c) 재고·요금을 짧게 캐시하는 것인데, (b)는 공급사가 지역 검색을 주지 않아 우리 카탈로그에 지역 속성을 두어야 한다. README에 이 산술을 적는다. **2026-09-06 실측**(`SearchScaleWireTest`): 122코드 → 3청크 57ms / 300코드 + 1s 지연 → 6청크 2파동, 데드라인 1.5s에서 4청크만 살아 1,516ms에 `PARTIAL` — §4의 B가 실제 와이어에서 도는 것을 처음 확인했다.
- **결과 캐시** — 요금·재고는 호출마다 바뀌는 동적 데이터라 캐시하지 않는다. 다만 같은 조건의 동시 요청을 하나로 합치는(request coalescing) 것은 공급사 부하 대책으로 검토 가치가 있다.
- **응답 크기** — 05 §13과 동일. `priceDetail` 포함 여부 파라미터.

**수천 개 — 동시성 상향(a)만으로는 안 된다는 것을 숫자로.** 청크 50 · 응답 타임아웃 2s · 데드라인 3s. "건강"은 호출당 100ms(실측 57ms/3청크보다 보수적).

| 숙소 수 | 청크 | 동시 4 — 파동 · 최악 · 건강 | 동시 10 — 파동 · 최악 · 건강 | 최악을 3s 안에 넣으려면 |
|---|---|---|---|---|
| 300 | 6 | 2 · 4s · 0.2s | 1 · 2s · 0.1s | 동시 6 |
| 1,000 | 20 | 5 · 10s · 0.5s | 2 · 4s · 0.2s | 동시 20 |
| 2,000 | 40 | 10 · 20s · 1.0s | 4 · 8s · 0.4s | 동시 40 |
| 5,000 | 100 | 25 · 50s · 2.5s | 10 · 20s · 1.0s | 동시 100 |

- 최악 케이스를 데드라인 안에 넣으려면 청크 수만큼의 동시 연결이 필요하다 — 5,000개면 한 공급사에 100개 동시. 429를 우리가 만든다.
- 건강한 공급사도 5,000개에서 동시 4면 2.5s라 데드라인에 거의 닿는다. 데드라인과 부분 응답(§4 B)은 안전망이지 해법이 아니다.
- 구조적인 답은 호출 수를 줄이는 (b) 후보 축소 · (c) 캐시/coalescing뿐이다. 검색 1건 = 공급사당 100호출이 되는 시점에는 (c)가 비용 문제로도 필요하다.

---

## 14. 구현하면서 달라진 것

| 항목 | 설계(위) | 구현 | 이유 |
|---|---|---|---|
| §1.1 요청 바인딩 | `@ModelAttribute` record + `validate()` + Bean Validation `@Min` | **`@RequestParam` 넷 + `SearchRequest` 생성자 검증.** Bean Validation 미사용 | record 바인딩은 누락·형식 오류가 모두 `MethodArgumentNotValidException`의 필드 오류 하나로 뭉개져 §1.1의 code 표(`MISSING_PARAMETER`/`INVALID_PARAMETER`)를 만들 수 없다. 펼치면 프레임워크가 두 예외로 구분해 준다. 생성자 검증이면 "만들 수 있으면 유효"라 `validate()` 호출을 잊을 길이 없다. §11의 "컨트롤러 파라미터 4개" 기각 사유(교차 검증이 컨트롤러 본문에)는 record 생성자가 검증하면 성립하지 않는다 |
| §6 예외 위치 | `service/InvalidSearchRequestException` | `dto/request/` | `SearchRequest`가 던진다. dto가 service 패키지를 참조하는 방향이 어색하다 |
| §8 핸들러 | `MethodArgumentNotValidException` 포함 | 제외 | Bean Validation을 쓰지 않아 발생하지 않는다 |
| §1.2 `offers` | 명시 없음 | **응답 items에 실린 항목 수.** `offers + unmapped + rejected` = 공급사가 준 항목 수 | 셋이 겹치지 않아야 합이 의미를 갖는다 |
| §1.2 `elapsedMs` | 명시 없음 | fetcher가 측정한 **공급사 벽시계 시간**(청크 병렬이라 합은 무의미) | 데드라인 초과 시 ≥ deadline으로 읽힌다 |
| §2 어댑터 예외 방어 | 공급사 단위 `onErrorResume` | **청크 단위** + 공급사 단위 안전망 | 한 청크의 계약 위반이 다른 청크를 지우지 않는다. 동기 예외(mapper에서 던짐)는 공급사 단위에서 잡힌다 |
| §9 경계 4 | 규칙 3개 나열 | `layeredArchitecture().consideringOnlyDependenciesInLayers()` | 레이어 밖 클래스(common 핸들러가 service 예외를 아는 것, 포트, dto)를 제외하지 않으면 정당한 의존이 위반으로 잡힌다 |
| §9 경계 5 | `@RestController` 메서드 시그니처 | **controller 패키지가 `@Entity`에 의존하지 않음** | 시그니처보다 강하고 규칙이 짧다. 컨트롤러 본문에서 엔티티를 만지는 것도 경계 위반이다 |
| §10 서비스 테스트 | 컨텍스트 + stub 어댑터 | **fetcher 단위 테스트 8개(Spring 없이)** | 데드라인·동시성·청크는 fetcher의 책임이고 순수하게 검증된다. `StaySearchService`는 세 줄이라 와이어 테스트가 덮는다 |
| §10 와이어 | 5 시나리오 | 6 — "빈 결과(adults=5) → OK + `no-store`" 추가 | §3의 `no-store` 조건 확장이 실제 HTTP에서 도는지 |

테스트 실제 수(app 138 = 기존 88 + 50): `SearchRequestTest` 6 · `SupplierAvailabilityFetcherTest` 8 · `SearchResultAssemblerTest` 12 ·
`StaySearchControllerTest` 13(파라미터화 포함) · `StaySearchWireTest` 6 · `ArchitectureTest` 5. Mock 30 포함 전체 168, 컴파일 경고 0.

## 15. 구현 기록 — 막힌 지점과 요약

**막힌 지점**

1. **`partition`이 패키지 프라이빗인데 테스트가 `search` 루트에 있었다.** catalog 테스트 관례(feature 루트에 평평하게)를 따랐더니 컴파일 실패.
   `partition`을 public으로 여는 대신 테스트를 `search.service`로 옮겼다 — 구현 세부를 테스트 때문에 공개하지 않는다.
2. **동시성 상한 측정이 3으로 나왔다(상한 2).** stub이 `doFinally`에서 in-flight를 줄였는데, `doFinally`는 완료 신호가 downstream으로
   **전파된 뒤** 실행된다. `flatMap`은 그 신호를 받는 즉시 다음 청크를 구독하므로 "다음 구독 → 이전 감소" 순서가 되어 한 개 초과로 측정됐다.
   `doOnTerminate` + `doOnCancel`(전파 전)로 바꿨다. fetcher의 `flatMap(…, 2)`는 처음부터 맞았다 — 측정 도구의 함정이었다.
3. **ArchUnit 경계 4를 개별 `noClasses()` 규칙으로 쓰려다 멈췄다.** `common.GlobalExceptionHandler`가 `catalog.service.SyncAlreadyRunningException`과
   `search.dto.request.InvalidSearchRequestException`을 아는 것은 정당한데 "service는 controller만 접근" 식 규칙에 걸린다.
   `layeredArchitecture().consideringOnlyDependenciesInLayers()`로 레이어 밖 클래스를 제외하니 규칙이 말하려는 것(계층 간 방향)만 남았다.

**요약**

- 선행 refactor 둘을 먼저 끝냈다(규칙 8). `CatalogLookupReader`는 function으로 옮기면서 `@Transactional`을 벗겼고 기존 테스트 2개가 그대로 통과했다.
  ArchUnit 5규칙은 어댑터 코드가 이미 경계를 지키고 있음을 첫 실행에서 확인했다.
- 데드라인 B 방식의 핵심은 `take(deadline)` 한 줄과 "청크 수 = 결과 수" 보정이다. 보정이 없으면 `failedCalls`가 실제보다 작아져 `status`가 거짓이 된다.
- 와이어 테스트에서 A(HTTP 503)와 B(200 + E503)가 같은 `SERVER_ERROR`로 응답에 실린다 — "실패 판정 통일"이 전 구간에서 도는 것을 처음 확인했다.
- 재시도·서킷·지표는 이 단계에 없다. `Skipped` 병합 코드는 들어가 있어 7단계에서 서킷이 `Skipped`를 내면 assembler 변경 없이 `SKIPPED`로 나온다.

---

## 미구현 항목

| 항목 | 이유 |
|---|---|
| 정렬·필터(지역·키워드)·페이징 | 범위 밖. 공급사가 지역 정보를 주지 않아 지역 필터는 카탈로그에 지역 속성을 두는 일이 선행된다. 정렬은 응답을 결정적으로 만드는 고정 순서(`propertyId → roomTypeId → supplier`)만 |
| 재시도 | 데드라인 3s 안에 예산이 없다(05 §13). 서킷(07)이 "이미 아는 실패에 시간을 쓰지 않는" 쪽을 맡았다 |
| 결과 캐시 · 요청 합치기(coalescing) | 요금·재고는 호출마다 바뀌는 동적 데이터고 총액은 검색 조건 4개에 종속된다. 캐시 키·TTL·정합성 오차를 정할 트래픽 근거가 없다. 같은 조건 동시 요청을 합치는 것은 공급사 부하 대책으로 다음 후보 |
| `priceDetail` 포함 여부 파라미터 | 지금 규모에서 응답 크기가 문제로 드러나지 않았다(05 §13) |
| `includeSoldOut` 파라미터 | 예약 불가 상품을 0으로 노출하는 것이 D-6의 결정이다. 수천 개로 늘어 응답이 비대해질 때의 일 |
| 전 공급사 `SKIPPED`를 구분하는 status 값(`ALL_SKIPPED`) | 07 §12. 클라이언트 판정 로직을 늘리는 값이라 미뤘다 — 실패 사실은 `suppliers[]`가 말한다 |
| 통화 환산 | 정책 문제(05) |
| 동시성 상한 4의 근거 | 출발점이다. `RATE_LIMITED` 비율(08 지표)이 쌓이면 조정한다(§13) |
