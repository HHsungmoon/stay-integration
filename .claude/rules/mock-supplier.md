---
paths:
  - "mock-supplier/**/*.java"
  - "mock-supplier/src/main/resources/**"
---

# Mock 공급사 서버 규칙

설계 전문은 로컬 `docs/03-MOCK.md`. 여기는 코드를 쓸 때 지켜야 하는 것만.

본체(`:app`)와 **별도 프로세스**(9090)로 뜬다. 같은 프로세스면 본체가 자기 자신을 HTTP로 호출하게 되어
무응답을 재현했을 때 공급사 지연인지 톰캣 스레드 고갈인지 구분할 수 없다.

## 두 원칙 — 충돌하면 2번이 우선

1. 시간을 쓰지 않는다. 코드 품질·데이터 다양성은 보지 않는다.
2. **문제를 감추지 않는다.** Mock의 결함이 본체 결함처럼 보이거나 본체 결함을 가리면 안 된다.

## 엔드포인트

| | A | B |
|---|---|---|
| 숙소 목록 | `GET /a/v1/hotels` | `GET /b/api/properties` |
| 재고·요금 | `GET /a/v1/availability?hotelCodes=&checkIn=&checkOut=&adults=&children=` | `GET /b/api/search?propertyIds=&…` |
| 인증 | `X-Api-Key: mock-key-a` | `X-Api-Key: mock-key-b` |
| 래핑 | 최상위 `items[]` | `{resultCode, resultMessage, data:{items[]}}` |

제어: `POST /control/{a|b}/mode?value={normal|error|no-response|delay}[&api={catalog|availability}][&delayMs=]`,
`GET /control`, `POST /control/reset`. 모드는 **(공급사 × API)** 단위 — 숙소 목록에만 장애를 걸어야 D-8을 검증할 수 있다.

## 반드시 지킬 것

**요청 기간에 맞춰 날짜를 생성한다. 고정 응답 금지.**
고정 날짜를 돌려주면 다른 기간으로 검색했을 때 D-5(누락 날짜는 0)가 정확히 작동해서 전부 매진이 된다.
값은 **결정적**으로 — `pattern[i % pattern.length]`. 랜덤 금지.

**장애 표현은 공급사마다 다르다.**
`error` 모드에서 A는 **HTTP 503** + `{"error":"SERVICE_UNAVAILABLE"}`, B는 **HTTP 200** + `{"resultCode":"E503","data":null}`.
같게 만들면 "실패 판정 통일"을 증명할 수 없다.

**`no-response`는 `Thread.sleep`으로 만들지 않는다.**
`DeferredResult`를 반환하고 완료시키지 않는다. sleep은 톰캣 스레드를 점유해 Mock의 스레드 풀이 말라붙고,
그 실패가 본체 문제로 오인된다. `reset`은 대기 중인 응답도 정리한다.

**요청 검증 3가지는 모드와 무관하게 항상.**
`X-Api-Key` 불일치 → 401/`E401`. 코드 **50개 초과** → 400 `TOO_MANY_HOTEL_CODES`/`E400`.
`checkOut <= checkIn` → 400 `INVALID_DATE_RANGE`/`E400`.
50개 검증이 청크 분할(D-9)의 1차 회귀 테스트다 — 예시 숙소만으로는 청크가 하나라, 규모 경로는 합성 숙소(`SearchScaleWireTest`)가 맡는다.

**인원 필터**: `adults + children <= maxOccupancy`인 객실 타입만 반환한다(스펙이 합산 기준).

## 데이터 — 바꾸기 전에 왜 그렇게 되어 있는지 확인

| 공급사 | 숙소 | 객실 타입 | max | 재고 패턴 | 요금 |
|---|---|---|---|---|---|
| A | `A-10023` | `DLX-TWN` | 2 | `[3,1,5]` | net `[120000,150000,120000]`, tax 10% |
| A | `A-10023` | `STD-DBL` | 2 | `[4,4,4]` | net `[90000,…]` |
| A | `A-10023` | `FAM-STE` | **4** | `[2,2,2]` | net `[200000,240000,200000]` |
| A | `A-10044` | **`STD-DBL`** | 2 | `[2,0,4]` | net `[88000,99000,88000]` |
| B | `B77120` | `R-401` | 2 | `[3,1,5]` | gross 155000/박, 조식 O |
| B | `B77120` | `R-402` | **4** | `[1,1,1]` | gross 260000/박, 조식 O |

- `STD-DBL`이 두 숙소에 있는 것은 **의도적** — 매핑 키가 `(supplier, hotelCode, roomTypeCode)`여야만 충돌하지 않는다.
- `maxOccupancy` 2/4 분리 — `adults=3`이면 `FAM-STE`·`R-402`만 나와야 한다.
- `A-10044`의 `[2,0,4]` — 2박 이상이면 매진(D-5 검증).
- `A-10023` = `B77120` 같은 호텔, 공통 키 없음. A 429,000 조식 X / B 465,000 조식 O — 합치지 않고 각각 노출.

## 구조

record로 응답을 만든다(Jackson 3 직렬화). 문자열 상수 금지 — 날짜를 동적으로 만들어야 하고, record 정의가 계약 문서 역할을 한다.
**어댑터의 DTO와 공유하지 않는다.** 공유하면 어댑터 테스트가 자기 자신을 검증하는 꼴이 된다.

## 하지 않는 것

페이징·정렬·지역 필터, 데이터 영속화, 요청 로깅, `429`·`500` 모드(실패 분류는 어댑터 단위 테스트가 Spring `ExchangeFunction` 스텁으로 덮는다).
- 합성 숙소(`POST /control/catalog/synthetic?count=N`)는 예시 데이터 뒤에 붙는 규모 테스트용이다. 예시 코드와 형식이 다르고 `reset`이 지운다. 데이터 다양성에 시간을 쓰지 않는다 — 객실 하나, 재고 3.
