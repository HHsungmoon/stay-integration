# Mock 공급사 서버 설계

작성일: 2026-09-05
상태: 구현 완료 (2026-09-05). 구현하면서 설계와 달라진 곳은 본문에 표시했다.

`:mock-supplier` 모듈. 본체(`:app`)가 호출할 두 공급사 API를 흉내내는 별도 서버다.
포트 9090에서 **별도 프로세스**로 뜬다.

> 이 문서는 외부 스펙을 옮겨 적은 것이 아니라, 스펙을 읽고 Mock을 어떻게 만들지 직접 판단한 기록이다.
> 필드명·엔드포인트는 어댑터가 맞춰야 하는 계약이므로 그대로 쓴다. 예시 데이터는 직접 설계했다.

---

## 0. Mock의 역할과 원칙

Mock은 산출물이 아니라 **본체를 검증하기 위한 도구**다. 그래서 두 원칙이 충돌한다.

1. **시간을 쓰지 않는다.** 코드 품질·데이터 다양성은 보지 않는다.
2. **문제를 감추지 않는다.** Mock의 결함이 본체의 결함처럼 보이거나, 본체의 결함을 가리면 안 된다.

2번이 우선이다. 아래 판단 중 "뼈대보다 복잡하게" 간 것들은 전부 2번 때문이다.

---

## 1. 두 공급사의 차이

표면은 필드명 차이지만, 실제로는 **표현 가능한 정보의 범위**가 다르다.

| | A | B |
|---|---|---|
| 숙소 목록 | `GET /a/v1/hotels` | `GET /b/api/properties` |
| 재고·요금 | `GET /a/v1/availability` | `GET /b/api/search` |
| 코드 파라미터 | `hotelCodes` (쉼표 구분, 최대 50) | `propertyIds` (쉼표 구분, 최대 50) |
| 응답 래핑 | 최상위 `items[]` | `data.items[]` |
| 숙소 식별자 | `hotelCode` / `hotelName` | `propertyId` / `propertyName` |
| 객실 타입 식별자 | `roomTypeCode` / `roomTypeName` | `roomId` / `roomName` |
| 요금 | `dailyRates[]` — 날짜별 `nightlyRate`(net) + `taxAmount` | `totalPrice` — 기간 총액 gross, `taxIncluded: true` |
| 재고 | `dailyRates[].remainingRooms` | `inventory[].remainingRooms` |
| 실패 | HTTP 4xx/5xx + `{error, message}` | **항상 HTTP 200** + `resultCode` (`0000`만 성공), `data: null` |

공통: `X-Api-Key` 헤더, `YYYY-MM-DD`, 체크아웃일 미포함, `adults`/`children` 분리, 금액은 통화 최소 단위 정수.
공급사는 요청 인원을 수용 가능한 객실 타입만 반환한다(`adults + children <= maxOccupancy`).

---

## 2. 핵심 판단

### 2.1 고정 응답을 쓰지 않는다 — 요청 기간에 맞춰 날짜를 생성한다

가장 단순한 Mock은 요청을 무시하고 고정 JSON을 돌려주는 것이다. **이 프로젝트에서는 그러면 정상 케이스가 전부 매진으로 나온다.**

```
고정 응답의 날짜 = 2026-09-01 ~ 09-03
고객이 2026-10-01 ~ 10-04로 검색
  → 어댑터는 10/1, 10/2, 10/3의 재고를 찾는다
  → 응답에는 9월 날짜만 있다 → 요청 범위 밖이므로 무시 (D-5)
  → 요청 날짜가 전부 누락 → 누락은 0으로 간주 (D-5, 과판매 방지)
  → 예약 가능 객실 수 = 0
```

우리가 정한 보수적 재고 규칙이 **정확히 작동해서** 매진이 된다. 로직은 맞는데 데모가 안 된다.
D-12(과거 날짜를 거부하지 않는다 — 테스트가 시간이 지나면 깨지니까)와도 정면충돌한다.
고정 응답을 쓰면 검색 날짜를 하나로 고정해야만 동작하는데, 그게 피하려던 상황이다.

**따라서 Mock은 요청의 `checkIn`~`checkOut`을 읽어 그 기간의 날짜별 데이터를 생성한다.**

### 2.2 값은 결정적으로 생성한다

랜덤을 쓰지 않는다. 같은 요청에 항상 같은 응답이 나와야 테스트가 재현 가능하다.

```
nights = checkOut - checkIn
i      = 체크인부터의 날짜 인덱스 (0-based)

remainingRooms(i) = roomsPattern[i % roomsPattern.length]

A: nightlyRate(i) = netPattern[i % netPattern.length]
   taxAmount(i)   = nightlyRate(i) / 10                  (세율 10%)

B: totalPrice     = grossNightly × nights                (날짜별 값 없음 — 스펙 그대로)
```

패턴을 순환시키면 스펙 예시의 의도(재고 3·1·5, 둘째 날 매진)를 보존하면서 어떤 기간으로 검색해도 동작한다.

### 2.3 `no-response`는 `Thread.sleep`으로 만들지 않는다

뼈대 예시는 `Thread.sleep(600_000)`이다. 이건 **톰캣 워커 스레드를 10분간 점유**한다.

통합 테스트에서 무응답 시나리오를 여러 번 돌리면 Mock의 스레드 풀이 말라붙고, 그 뒤의 테스트가 실패한다.
그때 원인이 본체인지 Mock인지 구분할 수 없다. Mock을 별도 프로세스로 뺀 이유가 정확히 이 종류의 사고를
막기 위해서였는데, Mock 안에서 같은 일이 벌어지는 셈이다.

`DeferredResult`를 반환하고 완료시키지 않는다. 연결은 열려 있고 응답만 오지 않는다 — 스펙이 말하는
"무응답"과 정확히 일치하고, 스레드는 즉시 반납된다.

```java
DeferredResult<ResponseEntity<?>> pending = new DeferredResult<>(600_000L);
return pending;   // 아무도 setResult를 호출하지 않는다
```

**구현 시 변경**: 처음엔 `Long.MAX_VALUE`로 적었으나 **600,000ms(10분)**로 바꿨다.
컨테이너가 만기 시각을 `now + timeout`으로 계산하는데, `Long.MAX_VALUE`면 오버플로우해 즉시 타임아웃될 수 있다.
10분이면 어떤 테스트도 그 안에 끝나고, 만기되면 Spring 기본 동작(503)으로 끊긴다.

검증: `no-response` 요청 20개를 걸어둔 상태에서 다른 공급사 정상 요청이 **28ms**에 응답했다(구현 당일, 로컬 `bootRun`).
2026-09-06 컨테이너 스택에서 다시 측정 — 20개가 6초 내내 열려 있는 동안(전부 클라이언트 타임아웃, 서버 응답 없음) B 정상 요청 5회 평균 **4.6ms**, 최대 7.3ms.
`Thread.sleep`이었다면 20개가 톰캣 스레드 풀의 10%를 10분간 점유했을 것이다. 계약 테스트 `noResponseDoesNotOccupyServerThreads`가 이것을 고정한다.

`delay` 모드는 같은 구조에 스케줄러로 N ms 뒤 `setResult`를 건다.

### 2.4 요청 파라미터는 `String`으로 받는다

`@RequestParam LocalDate checkIn`처럼 타입을 주면 형식이 틀렸을 때 **Spring이 자체 400 응답**을 돌려준다.
A는 그래도 괜찮지만 **B는 실패해도 HTTP 200이어야 한다.** 스펙 위반이라 전부 `String`으로 받아 직접 파싱하고,
공급사별 형태로 거절한다. 파싱·검증 로직은 A·B가 공유하므로 `common/AvailabilityQuery`에 둔다.

### 2.5 요청 검증은 최소한만 — 그러나 이 셋은 넣는다

| 조건 | A | B | 왜 필요한가 |
|---|---|---|---|
| `X-Api-Key` 없음·불일치 | 401 `UNAUTHORIZED` | 200 + `E401` | 어댑터가 헤더를 실제로 붙이는지. **영구적 실패** 분류 테스트 재료 |
| 코드 **50개 초과** | 400 `TOO_MANY_HOTEL_CODES` | 200 + `E400` | 청크 분할(D-9)의 회귀 방지. 예시 숙소가 3개뿐이라 이게 없으면 청크 로직이 깨져도 아무 일도 안 일어난다 |
| `checkOut <= checkIn` | 400 `INVALID_DATE_RANGE` | 200 + `E400` | 본체 검증(D-13)이 먼저 막아야 하지만, 뚫렸을 때 공급사가 어떻게 반응하는지 |

50개 검증이 특히 중요하다. 본체 어댑터는 51개 요청을 **호출 없이** `BAD_REQUEST`로 거절하고(`SupplierCircuitBreakerTest`), 청크 분할 자체는
합성 숙소(§10)로 실제 HTTP에서 검증된다(`SearchScaleWireTest`). 설계 시점에는 "가짜 코드 60개 주입"을 생각했지만 구현은 합성 숙소 쪽으로 갔다 —
가짜 코드는 Mock이 무시해 items가 비므로 청크 수 외에는 검증할 것이 없다.

인증 키는 공급사별로 다르게 둔다. 어댑터가 공급사별 설정을 제대로 쓰는지도 함께 검증된다.

| 공급사 | 기대 키 (기본값, 설정으로 변경 가능) |
|---|---|
| A | `mock-key-a` |
| B | `mock-key-b` |

알 수 없는 숙소 코드는 오류 없이 무시한다(응답에서 빠진다). 실제 공급사도 그렇게 동작할 것이고,
어댑터의 "매핑에 없는 상품 수신"(D-10) 반대 방향 케이스다.

---

## 3. 데이터 설계 — 데이터가 곧 테스트 시나리오

여기에는 시간을 쓸 가치가 있다. **Mock 데이터가 우리 설계 판단의 회귀 테스트가 되기 때문**이다.

| 공급사 | 숙소 | 객실 타입 | max | 재고 패턴 | 요금 | 조식 |
|---|---|---|---|---|---|---|
| A | `A-10023` Riverside Hotel Seoul | `DLX-TWN` Deluxe Twin | 2 | `[3, 1, 5]` | net `[120000, 150000, 120000]` | X |
| A | `A-10023` | `STD-DBL` Standard Double | 2 | `[4, 4, 4]` | net `[90000, 90000, 90000]` | X |
| A | `A-10023` | `FAM-STE` Family Suite | **4** | `[2, 2, 2]` | net `[200000, 240000, 200000]` | X |
| A | `A-10044` Namsan Garden Stay | **`STD-DBL`** Standard Double | 2 | `[2, 0, 4]` | net `[88000, 99000, 88000]` | X |
| B | `B77120` Riverside Hotel Seoul | `R-401` Deluxe Twin Room | 2 | `[3, 1, 5]` | gross 155000/박 | **O** |
| B | `B77120` | `R-402` Family Room | **4** | `[1, 1, 1]` | gross 260000/박 | **O** |

통화는 전부 `KRW`.

### 각 행이 검증하는 것

**① `STD-DBL`이 두 숙소(`A-10023`, `A-10044`)에 있다 — 의도적이다.**

객실 타입 코드는 **숙소 안에서만 유일**하다는 것이 스펙의 명시적 함정이고, 그래서 매핑 키가
`(supplier, hotelCode, roomTypeCode)` 세 값이어야 한다. 키를 `(supplier, roomTypeCode)`로 잘못 잡으면
이 두 행이 충돌한다. **키 설계 버그가 카탈로그 동기화 단계에서 바로 드러난다.**

**② `maxOccupancy`가 2와 4로 갈린다.**

`adults=3`으로 검색하면 `FAM-STE`와 `R-402`만 나와야 한다. Mock이 인원 필터를 구현하므로,
어댑터가 인원 파라미터를 실제로 전달하는지 검증된다.

**③ `A-10044 STD-DBL`의 재고 `[2, 0, 4]` — 검색 기간에 따라 결과가 달라진다.**

| 검색 | 최솟값 | 결과 |
|---|---|---|
| 1박 | min(2) = 2 | 예약 가능 |
| 2박 | min(2, 0) = **0** | 매진 |
| 3박 | min(2, 0, 4) = **0** | 매진 |

연박 판정(D-5)이 한 데이터로 확인된다.

**④ `A-10023`과 `B77120`은 같은 호텔이다.** 공통 키는 없다.

같은 객실(Deluxe Twin)인데 A는 3박 429,000원 조식 미포함, B는 465,000원 조식 포함.
싼 쪽을 고르면 조건이 다른 상품을 비교하게 된다. 중복 병합을 하지 않고 각각 노출한다는 판단(4.7)이
응답에서 눈으로 확인된다.

**⑤ `Namsan Garden Stay`는 A에만 있다.** 한 공급사만 취급하는 상품이 정상적으로 노출되는지.

---

## 4. 엔드포인트

### 4.1 Supplier A

**`GET /a/v1/hotels`** — 조건 없음. 요금·재고·조식 없음.

```json
{
  "items": [
    {
      "hotelCode": "A-10023",
      "hotelName": "Riverside Hotel Seoul",
      "roomTypes": [
        { "roomTypeCode": "DLX-TWN", "roomTypeName": "Deluxe Twin",      "maxOccupancy": 2 },
        { "roomTypeCode": "STD-DBL", "roomTypeName": "Standard Double",  "maxOccupancy": 2 },
        { "roomTypeCode": "FAM-STE", "roomTypeName": "Family Suite",     "maxOccupancy": 4 }
      ]
    },
    {
      "hotelCode": "A-10044",
      "hotelName": "Namsan Garden Stay",
      "roomTypes": [
        { "roomTypeCode": "STD-DBL", "roomTypeName": "Standard Double",  "maxOccupancy": 2 }
      ]
    }
  ]
}
```

**`GET /a/v1/availability?hotelCodes=A-10023,A-10044&checkIn=…&checkOut=…&adults=2&children=0`**

items는 **(숙소 × 객실 타입) flat**이다. 인원을 수용하지 못하는 객실 타입은 제외된다.

```json
{
  "items": [
    {
      "hotelCode": "A-10023", "hotelName": "Riverside Hotel Seoul",
      "roomTypeCode": "DLX-TWN", "roomTypeName": "Deluxe Twin",
      "maxOccupancy": 2, "breakfastIncluded": false, "currency": "KRW",
      "dailyRates": [
        { "date": "<checkIn+0>", "remainingRooms": 3, "nightlyRate": 120000, "taxAmount": 12000 },
        { "date": "<checkIn+1>", "remainingRooms": 1, "nightlyRate": 150000, "taxAmount": 15000 },
        { "date": "<checkIn+2>", "remainingRooms": 5, "nightlyRate": 120000, "taxAmount": 12000 }
      ]
    }
  ]
}
```

**실패**: HTTP 상태 + `{ "error": "<CODE>", "message": "…" }`

| 상태 | `error` |
|---|---|
| 400 | `INVALID_DATE_RANGE` · `INVALID_PARAMETER` · `TOO_MANY_HOTEL_CODES` |
| 401 | `UNAUTHORIZED` |
| 503 | `SERVICE_UNAVAILABLE` (error 모드) |

### 4.2 Supplier B

**`GET /b/api/properties`**

```json
{
  "resultCode": "0000", "resultMessage": "SUCCESS",
  "data": {
    "items": [
      {
        "propertyId": "B77120", "propertyName": "Riverside Hotel Seoul",
        "rooms": [
          { "roomId": "R-401", "roomName": "Deluxe Twin Room", "maxOccupancy": 2 },
          { "roomId": "R-402", "roomName": "Family Room",       "maxOccupancy": 4 }
        ]
      }
    ]
  }
}
```

**`GET /b/api/search?propertyIds=B77120&checkIn=…&checkOut=…&adults=2&children=0`**

```json
{
  "resultCode": "0000", "resultMessage": "SUCCESS",
  "data": {
    "items": [
      {
        "propertyId": "B77120", "propertyName": "Riverside Hotel Seoul",
        "roomId": "R-401", "roomName": "Deluxe Twin Room",
        "maxOccupancy": 2, "breakfastIncluded": true, "currency": "KRW",
        "totalPrice": 465000, "taxIncluded": true,
        "inventory": [
          { "date": "<checkIn+0>", "remainingRooms": 3 },
          { "date": "<checkIn+1>", "remainingRooms": 1 },
          { "date": "<checkIn+2>", "remainingRooms": 5 }
        ]
      }
    ]
  }
}
```

`totalPrice = 155000 × nights`. 날짜별 요금은 **없다** — 스펙 그대로.

**실패**: **항상 HTTP 200**. `data`는 `null`.

```json
{ "resultCode": "E503", "resultMessage": "TEMPORARILY_UNAVAILABLE", "data": null }
```

| `resultCode` | 의미 |
|---|---|
| `E400` | 잘못된 요청 (50개 초과 포함) |
| `E401` | 인증 실패 |
| `E503` | 일시적 장애 (error 모드) |

### 4.3 제어

```
POST /control/{a|b}/mode?value={normal|error|no-response|delay}[&api={catalog|availability}][&delayMs=3000]
GET  /control
POST /control/reset
```

- `api`를 생략하면 그 공급사의 두 API 모두에 적용된다.
- `delay`의 기본 지연은 **3000ms** — 응답 타임아웃(2s)을 확실히 넘기는 값이다.
- `GET /control`은 현재 모드 전체를 반환한다. 테스트가 사전 조건을 확인할 때 쓴다.
- `reset`은 전부 `normal`로 되돌리고, **대기 중인 `no-response` 응답도 정리**한다. 테스트 간 격리용.

모드는 **(공급사 × API)** 단위로 저장한다. 뼈대 예시(공급사 단위)보다 세분화한 이유:
숙소 목록에만 장애를 걸어야 **D-8(동기화 실패해도 앱은 뜨고 기존 매핑으로 서비스)** 을 검증할 수 있다.

---

## 5. 모드별 동작

| 모드 | A | B |
|---|---|---|
| `normal` | 200 + 정상 응답 | 200 + `resultCode: "0000"` |
| `error` | **503** + `{"error":"SERVICE_UNAVAILABLE"}` | **200** + `{"resultCode":"E503","data":null}` |
| `no-response` | 연결 유지, 응답 없음 (DeferredResult 미완료) | 동일 |
| `delay` | N ms 뒤 정상 응답 | 동일 |

**`error`의 표현이 공급사마다 다른 것이 핵심이다.** 둘을 같게 만들면 어댑터의 "실패 판정 통일"이
실제로 동작하는지 증명할 수 없다 — Mock이 문제를 감춰버린다.

요청 검증 실패(§2.4)는 모드와 무관하게 항상 적용된다. `normal`이어도 키가 틀리면 401/E401이다.

---

## 6. 구현 구조

```
mock-supplier/src/main/java/com/demo/mocksupplier/
├── MockSupplierApplication.java     @ConfigurationPropertiesScan
├── control/
│   ├── MockMode.java                enum NORMAL · ERROR · NO_RESPONSE · DELAY
│   ├── MockModeRegistry.java        (supplier, api) → (mode, delayMs). no-response 대기 응답 추적·정리
│   └── MockControlController.java   /control/**
├── common/                          ← 구현 시 추가. A·B가 공유하는 것
│   ├── MockProperties.java          mock.api-key.{a,b}, mock.delay-default-ms
│   ├── RequestError.java            공급사 중립 거절 사유 enum. A·B가 각자 표현으로 번역
│   ├── AvailabilityQuery.java       파라미터 파싱·검증 (§2.4). 결과는 sealed Ok | Rejected
│   └── ResponseGate.java            모드에 따라 응답을 게이트 (§2.3). 모든 모드를 DeferredResult로 통일
├── catalog/
│   └── MockCatalog.java             §3 데이터 + 날짜별 값 생성 (§2.2)
├── a/
│   ├── SupplierAController.java     /a/v1/**
│   └── SupplierAResponse.java       응답 record 묶음 (hotels · availability · error)
└── b/
    ├── SupplierBController.java     /b/api/**
    └── SupplierBResponse.java       응답 record 묶음 (envelope · properties · search)
```

**구현 시 변경**: 설계에는 8개였으나 `common/`이 추가되어 13개다. 요청 파싱·검증, 모드 게이트, 설정 바인딩을
A·B 컨트롤러에 복붙하면 한쪽만 고치는 사고가 나서 뺐다. `RequestError`를 enum으로 두고 A·B가 각자 `switch`로
번역하므로, 거절 사유를 추가하면 **한쪽만 고쳤을 때 컴파일 에러**로 드러난다.

문자열 상수 대신 `record`를 쓴다. 날짜를 동적으로 만들어야 해서이기도 하고,
**record 정의 자체가 응답 계약의 문서 역할**을 하기 때문이다. Jackson 3이 직렬화한다.

Mock의 record는 어댑터의 DTO와 **공유하지 않는다.** 별도 모듈이라 구조적으로 불가능하고,
공유하면 어댑터 테스트가 자기 자신을 검증하는 꼴이 된다.

설정 (`application.yaml`):

```yaml
server:
  port: 9090
mock:
  api-key:
    a: mock-key-a
    b: mock-key-b
  delay-default-ms: 3000
```

---

## 7. 통합 테스트가 이 Mock으로 증명하는 것

| 시나리오 | 준비 | 기대 |
|---|---|---|
| 정상 병합 | 둘 다 `normal` | `status: OK`, 6개 상품, 같은 호텔이 A·B 각각 노출 |
| A 무응답 | `a → no-response` | `status: PARTIAL`, B 상품만, 실패 목록에 A. 응답 시간 ≈ 응답 타임아웃(운영 2s. 와이어 테스트는 700ms로 줄여 돈다) |
| B 본문 실패 | `b → error` | `status: PARTIAL`, A 상품만, 실패 목록에 B. **HTTP 200이었지만 실패로 인식** |
| 전부 실패 | 둘 다 `error` | `status: ALL_FAILED`, 상품 0, `Cache-Control: no-store` |
| 연박 매진 | 2박 이상 검색 | `A-10044 STD-DBL`이 `available: false`, 재고 0 |
| 인원 필터 | `adults=3` | `FAM-STE`, `R-402`만 — Mock 계약 테스트(`filtersRoomTypesByTotalGuests`)에서. 본체 와이어는 `adults=5` → 빈 결과 + `no-store`로 확인 |
| 청크 분할 | 합성 숙소 120·298개(§10) 후 재동기화·검색 | 공급사당 청크 3개 / 6개가 실제 HTTP로 나간다. 지연 + 데드라인이면 도착한 청크만 산다(`SearchScaleWireTest`) |
| 동기화 실패 | `a → error&api=catalog` 후 재동기화 | A 동기화 실패, **B는 성공**, 앱은 정상. 기존 A 매핑 유지 |
| 서킷 브레이커 | `a → error`로 N회 실패 후 | A 호출이 즉시 `Skipped`. `a → normal` 후 반열림 → 회복 |

---

## 8. 하지 않는 것

- 페이징, 정렬, 지역 필터 — 스펙에 없다.
- 데이터 영속화 — 재시작하면 모드가 `normal`로 돌아간다. 그게 맞다.
- 요청 로깅·계측 — Mock에 관측성은 필요 없다. 본체의 관측성이 Mock 호출을 이미 기록한다.
- 스펙에 있는 실패 코드 전부 재현 — `429`/`E429`, `500`/`E500`은 모드로 만들지 않는다.
  어댑터의 실패 **분류** 로직은 Spring `ExchangeFunction` 스텁(`StubExchange`)으로 코드별로 검증한다 — MockWebServer·WireMock은 BOM 밖이라 넣지 않았다(05 §14).
  통합 테스트용 Mock은 "일시적 장애 한 가지"만 있으면 충분하다.

---

## 9. 남은 결정 → 둘 다 결정됨

- `delay` 모드의 지연 시간 → **(공급사 × API) 단위 하나.** 요청별로 다르게 줄 필요는 생기지 않았다. 대신 "청크 하나만 느린" 상황을 여기서 못 만드는 한계가 됐다(§10) — 그건 본체 단위 테스트가 맡는다.
- 인원 필터 → **합산**(`adults + children <= maxOccupancy`). `AvailabilityQuery.guests()`가 합을 돌려주고 A·B 컨트롤러가 같은 값으로 거른다.

---

## 10. 합성 카탈로그 (규모 테스트용, 2026-09-06)

스펙 예시는 숙소 3개라 50개 청크 경로가 실제 HTTP로 흐른 적이 없었다. 예시 데이터는 그대로 두고 **뒤에 합성 숙소를 붙인다.**

```
POST /control/catalog/synthetic?count=N      # 공급사마다 N개. 0 = 예시 그대로. reset이 0으로 되돌린다
GET  /control                                 # catalog.synthetic 값으로 확인
```

- 코드 형식이 예시와 다르다(A `A-9xxxxx`, B `B9xxxxx`) — 우연히 겹칠 수 없다.
- 숙소마다 객실 하나(items 수 = 숙소 수), 2인 수용, 재고는 항상 3, 요금은 인덱스마다 조금씩 달라 합산·정렬이 우연히 맞는 일이 없다.
- 목록(①)에도 재고·요금(②)에도 같은 규칙으로 나타난다. 본체는 동기화를 다시 돌려야 새 숙소를 안다.
- 생성은 요청 때마다 계산한다(저장 없음). 5,000개까지.

이것으로 증명된 것: `SearchScaleWireTest` — 122코드 → 3청크(57ms), 300코드 + `delay 1000ms` → 6청크 2파동, 데드라인 1.5s에서 첫 파동 4청크만 살고 2청크 `TIMEOUT`.
한계: `delay`는 (공급사 × API) 단위라 "청크 하나만 느린" 상황은 여기서 못 만든다 — 그건 본체 단위 테스트(`SupplierAvailabilityFetcherTest`)가 맡는다.

---

## 미구현 항목

| 항목 | 이유 |
|---|---|
| `429`/`E429`, `500`/`E500` 모드 | 통합 테스트에는 "일시적 장애 한 가지"(`503`/`E503`)면 충분하다. 실패 **분류**는 본체의 `ExchangeFunction` 스텁 테스트가 코드별로 덮는다 — Mock이 그걸 다시 할 이유가 없다 |
| 청크 단위 지연 | 모드가 (공급사 × API) 단위다. 요청마다 다른 지연을 주려면 요청 내용을 보고 분기해야 하고, 그건 Mock이 본체 청크 논리를 알게 되는 것이다. 본체 단위 테스트(`SupplierAvailabilityFetcherTest`)가 맡는다 |
| 예약 생성·취소 API | 공급사 스펙에 계약이 없다. 설계는 `docs/09`, 필요한 Mock 모드(`created-but-no-response`)도 거기 적었다 |
| 요청 로깅·계측 | 본체의 관측성이 Mock 호출을 이미 기록한다 |
| 데이터·모드 영속화 | 재시작하면 `normal`·합성 0으로 돌아가는 것이 맞다 — 테스트 간 격리 |
| 페이징·정렬·지역 필터 | 스펙에 없다 |
