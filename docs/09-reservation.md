# 예약 대행 — 설계 (구현하지 않음)

작성일: 2026-09-05
상태: **설계만.** 공급사 스펙에 예약 생성·취소 API 계약이 없어 구현할 대상이 없다. 이 문서는 "기존 구조 위에 어떻게 얹는가"를 답한다.

## 요약 — 검색과 무엇이 다른가

검색은 **읽기**다. 실패하면 부분 결과로 응답하고 끝난다. 예약은 **외부 상태를 바꾸는 쓰기**다. 실패의 종류가 하나 늘어난다 —
"안 됐다"와 "됐는지 모른다"가 다르다. 공급사에 예약 생성을 보냈는데 타임아웃이 나면, 공급사에는 예약이 생겼을 수도 있다.
그 예약을 우리가 모르면 고객은 결제도 안 했는데 방이 잡혀 있고, 공급사는 노쇼 처리를 한다.

그래서 우선순위가 뒤집힌다. 검색은 **데드라인 3초**가 최우선이고 정확성은 부분 실패로 타협했다. 예약은 **상태 정확성**이 최우선이고 시간은 타협한다.
검색에서 버렸던 재시도가 여기서는 필수 도구가 되고, 검색에서 금지했던 "DB에 매핑 외의 것을 저장"이 여기서는 첫 예외가 된다.

기존 구조에서 그대로 쓰는 것: 포트 + `SupplierResult` · `FailureKind.retryable()` · `SupplierCallPipeline`(타임아웃·서킷) · `SupplierCallMetrics` · 카탈로그 매핑 · feature 레이어 구조.
새로 생기는 것: 예약 상태 머신 하나, 테이블 하나, 포트 인터페이스 하나, 조정(reconcile) 작업 하나.

---

## 0. 전제 — 공급사 예약 API를 이렇게 가정한다

스펙에 없으므로 **우리가 정의하는 가정**이다. 실제 계약이 오면 이 절만 바뀌고 나머지는 그대로여야 한다 — 그것이 포트를 두는 이유다.

| 호출 | 입력 | 응답 | 실패 표현 |
|---|---|---|---|
| 예약 생성 | 숙소 코드 · 객실 코드 · 체크인/아웃 · 인원 · 예상 총액 · **우리 예약 ID(clientReference)** | 공급사 예약 ID · 확정 총액 · 상태 | A는 HTTP 상태, B는 `resultCode` — 검색과 같은 방식 |
| 예약 취소 | 공급사 예약 ID | 취소 확정 | 동일 |
| 예약 조회 | 공급사 예약 ID **또는 clientReference** | 상태 · 총액 | 동일 |

세 번째(조회)가 없으면 "됐는지 모른다"를 해소할 방법이 취소 시도 하나뿐이라, **조회 API는 계약 협상에서 반드시 요구**한다.
`clientReference`를 받아 주지 않는 공급사면 중복 생성을 공급사 쪽에서 막을 수 없어 우리 쪽 멱등 키만으로 버텨야 한다(§4).

---

## 1. 흐름

```
POST /api/v1/reservations            ← Idempotency-Key 헤더
  ① 요청 검증 — roomTypeId · 날짜 · 인원 · 예상 총액          400
  ② 멱등 키 조회 — 같은 키가 있으면 그 예약을 그대로 반환      (재전송 안전)
  ③ 매핑 역방향 조회 — roomTypeId → (공급사, 숙소 코드, 객실 코드)   catalog.function
  ④ 예약 행 INSERT: status = PENDING, idempotency_key UNIQUE     ← 트랜잭션 1 (짧다, 외부 호출 없음)
  ⑤ 공급사 createBooking(...)  →  SupplierResult<BookingConfirmation>
       Success               → ⑥ CONFIRMED, supplier_booking_id 저장          ← 트랜잭션 2
       Failure(retryable=false) → FAILED (공급사가 "안 된다"고 답했다: 매진·잘못된 요청)
       Failure(retryable=true)  → UNKNOWN (타임아웃·연결 실패 — 공급사 상태를 모른다)   ← 조정 대상 (§3)
       Skipped(circuit open)  → REJECTED, 즉시 (부작용 없음 — 호출을 안 했다)
  ⑦ 응답: 예약 ID · status · 총액.  UNKNOWN도 202로 정직하게 돌려준다 — 고객에게 "확인 중"
```

- **④가 ⑤보다 먼저**다. 공급사를 부른 뒤에 INSERT하면 "공급사에 생겼는데 우리 DB에 없다"는 가장 나쁜 상태가 생긴다. 먼저 PENDING을 남기면 어떤 실패에서도 조정할 실마리(우리 ID)가 있다.
- **트랜잭션 안에서 공급사를 부르지 않는다.** 검색과 동기화가 지킨 규칙 그대로다 — ④와 ⑥은 각각 짧은 트랜잭션이고 ⑤는 밖이다.
- `UNKNOWN`을 실패로 뭉개지 않는 것이 이 설계의 전부다. `FailureKind.retryable()`이 여기서 세 번째 의미를 얻는다:
  재시도 가능 / 서킷 기록 / **"공급사 상태를 모른다"**. 셋이 같은 질문("공급사 쪽 문제인가")이라 술어는 여전히 하나다.

취소는 대칭이다.

```
DELETE /api/v1/reservations/{id}
  CONFIRMED → CANCEL_REQUESTED(트랜잭션) → cancelBooking → CANCELLED | CANCEL_FAILED(재시도 대상)
  UNKNOWN   → 취소 요청은 받되 조정이 먼저 상태를 확정한 뒤 처리 (§3)
  PENDING   → 아직 공급사를 부르기 전이면 그냥 CANCELLED
```

---

## 2. 상태 머신

```
PENDING ──Success──▶ CONFIRMED ──취소 요청──▶ CANCEL_REQUESTED ──Success──▶ CANCELLED
   │                     ▲                          │
   ├──retryable 실패────▶ UNKNOWN ──조회: 있음────────┘ (CONFIRMED로 승격 후 필요하면 취소)
   │                     │
   │                     └──조회: 없음 / 취소 성공──▶ CANCELLED(보상)
   ├──non-retryable 실패──▶ FAILED
   └──Skipped(서킷)───────▶ REJECTED
                                                    CANCEL_REQUESTED ──retryable 실패──▶ CANCEL_FAILED ──재시도──▶ CANCELLED
```

- 종단 상태: `CONFIRMED` `CANCELLED` `FAILED` `REJECTED`. 나머지는 **조정 작업이 반드시 종단으로 옮긴다**.
- 전이는 엔티티의 도메인 메서드(`confirm(bookingId)`, `markUnknown(reason)`, `reconcileAsConfirmed`…)로만. 카탈로그 엔티티의 `refresh`/`deactivate`와 같은 방식이다.
- `UNKNOWN`에서 취소 성공은 두 경우를 덮는다 — 공급사에 있었으면 취소된 것이고, 없었으면 취소가 "없음"을 돌려주니 결과는 같다. 그래서 **조회 API가 없어도 취소 하나로 보상이 성립**한다(느리고 공급사 로그에 헛취소가 남지만).

---

## 3. 조정(reconcile) — "됐는지 모른다"를 끝내는 작업

`UNKNOWN`·`CANCEL_FAILED` 행이 곧 **outbox**다. 별도 테이블을 두지 않는다 — 처리할 일이 예약 행의 상태에 다 있다.

```
@Scheduled(fixedDelay = 30s)  ReservationReconciler
  for row in (UNKNOWN or CANCEL_FAILED) where next_attempt_at <= now:
      UNKNOWN:       getBooking(clientReference = 우리 ID)
                        있음 → CONFIRMED (고객이 취소했으면 이어서 cancel)
                        없음 → CANCELLED(보상)  — 조회 API가 없으면 cancelBooking으로 대신
                        retryable 실패 → attempts++, next_attempt_at = now + backoff(attempts)
      CANCEL_FAILED: cancelBooking 재시도, 같은 규칙
  attempts > N (예: 10회, 지수 백오프로 ~1시간) → NEEDS_ATTENTION 로그 + 지표. 사람이 본다
```

- **재시도가 여기서는 옳다.** 검색에서 재시도를 버린 이유는 3초 데드라인 안에 예산이 없어서였다. 조정은 사용자가 기다리지 않는 백그라운드라 예산이 시간 단위다.
  `retryable()`이 false인 실패(400·401·모르는 코드)는 재시도하지 않는다 — 규칙은 검색과 같다.
- 이 앱의 **첫 스케줄러**다. 단일 인스턴스 가정(README)이 여기서도 필요하다 — 두 인스턴스가 같은 행을 조정하면 취소가 두 번 나간다. 다중 인스턴스면 행 단위 `SELECT … FOR UPDATE SKIP LOCKED`.
- 서킷이 열려 있으면 조정도 `Skipped`를 받는다. 그 행은 그대로 두고 다음 주기에 본다 — 서킷은 어댑터의 일이라 조정기는 모른다(경계 6).

---

## 4. 멱등성 — 두 겹

| 겹 | 키 | 막는 것 |
|---|---|---|
| 우리 → 고객 | `Idempotency-Key` 헤더, `reservation.idempotency_key UNIQUE` | 클라이언트 재전송으로 예약이 두 개 생기는 것. 같은 키 = 같은 응답 |
| 우리 → 공급사 | `clientReference` = 우리 예약 ID | 우리가 타임아웃 뒤 재시도했을 때 공급사에 두 개 생기는 것 |

두 번째 겹을 공급사가 지원하지 않으면 `UNKNOWN` 상태에서 **절대 create를 재시도하지 않는다** — 조회·취소로만 조정한다. 중복 예약은 되돌리기가 가장 비싸다.

---

## 5. 요금 — 검색 시점과 예약 시점이 다르다

검색이 보여 준 총액은 그 순간의 값이다(README "총액은 검색 조건 종속값"). 예약 요청에 **예상 총액**을 담고, 공급사가 돌려준 확정 총액과 비교한다.

- 같다 → CONFIRMED.
- 다르다 → **정책 결정이 필요하다.** 선택지는 (a) 즉시 취소하고 `PRICE_CHANGED`로 409, (b) 허용 오차 안이면 승인. 이 문서는 (a)를 기본으로 둔다 — 고객 동의 없이 다른 금액을 확정하지 않는다. 오차 허용은 상품·통화 정책이라 여기서 정하지 않는다.
- 예약 행에 **요금 스냅샷**(총액·통화·세금 포함 여부)을 저장한다. 나중에 분쟁이 나면 "그때 얼마였나"의 근거는 이것뿐이다 — 요금 원본을 저장하지 않는다는 원칙의 예외이고, 예외인 이유가 명확하다.

---

## 6. 기존 구조 위에 얹는 자리

| 층 | 새로 생기는 것 | 기존에서 그대로 쓰는 것 |
|---|---|---|
| `supplier.port` | `SupplierBookingAdapter` 인터페이스(create · cancel · get) + `BookingRequest` · `BookingConfirmation` 표준 모델 | `SupplierResult<T>` · `FailureKind` · `SupplierId` |
| `supplier.adapter.{a,b}` | 각 어댑터가 `SupplierBookingAdapter`도 구현. 전용 DTO는 패키지 안 | `SupplierCallPipelines.forSupplier(id)` — 타임아웃·서킷이 자동으로 붙는다 |
| `supplier.adapter.support` | 예약용 응답 타임아웃(예: 10s — 검색의 2s와 다르다. 쓰기는 기다리는 편이 낫다) | `FailureClassifier` · `CallOutcome` |
| `catalog.function` | `ReservationTargetReader` — 내부 roomTypeId → (공급사, 숙소 코드, 객실 코드) 역방향 조회 | 매핑 테이블. 새 쿼리 하나 |
| `reservation` (새 feature) | `controller / service / function / repository / entity / dto` — 레이어 규칙 그대로 | `GlobalExceptionHandler`(409 `PRICE_CHANGED`, 404), `SupplierCallMetrics`(`Api.BOOKING` · `Api.CANCEL`) |
| `common` | — | `SupplierCallMetrics`에 outcome 두 종류 추가만 |

- **포트를 나누는 이유**: `SupplierAdapter`에 create/cancel을 더하면 예약을 지원하지 않는 공급사도 구현을 강요받는다. 별도 인터페이스면 지원하는 어댑터만 구현하고,
  레지스트리가 "이 공급사는 예약을 받나"를 타입으로 답한다. "신규 공급사 추가 시 고칠 것"은 그대로다 — 어댑터 하나 + 설정.
- **`.block()` 위치**: 예약은 호출이 하나라 병렬이 없다. 컨트롤러 경계에서 한 번. 조정기는 스케줄러 스레드에서 한 번. 체인 안에 JPA 없음은 그대로.
- **서킷 공유**: 검색 실패로 열린 공급사 서킷은 예약도 막는다(`REJECTED`). 맞는 동작이다 — 죽은 공급사에 예약을 넣는 것보다 즉시 거절이 낫다. 다만 예약 타임아웃(10s)과 검색 타임아웃(2s)이 같은 창에 들어가는 점은 지표로 지켜본다.
- ArchUnit 경계는 새 규칙 없이 그대로 적용된다 — `reservation`은 `supplier.adapter`를 모르고, repository는 function에서만.

---

## 7. 테이블 — "매핑만 저장"의 첫 예외

```
reservation
  id                    bigserial PK
  idempotency_key       varchar UNIQUE          ← 고객 재전송 방어
  room_type_mapping_id  FK → room_type_mapping   ← 어느 공급사·숙소·객실인지는 여기서 따라간다
  supplier              varchar
  supplier_booking_id   varchar NULL            ← CONFIRMED 뒤에 채워진다
  status                varchar                 ← §2
  check_in / check_out  date
  adults / children     int
  quoted_amount / confirmed_amount / currency   ← §5 스냅샷
  attempts / next_attempt_at / last_error       ← §3 조정
  created_at / updated_at
```

예외인 이유: 이 행이 없으면 "공급사에 있는지 모르는 예약"을 찾을 방법이 없다. 매핑 외의 저장을 피한 것은 **원본이 외부에 있는 데이터를 복제하지 않기 위해서**였고, 예약은 우리가 만든 사건이라 원본이 우리다.

---

## 8. Mock에 필요한 것

`POST /a/v1/bookings` · `DELETE …/{id}` · `GET …?clientReference=` (B는 `/b/api/bookings`, 봉투 안). 모드는 기존 셋에 하나를 더한다 —
**`created-but-no-response`**: 예약은 만들고 응답을 주지 않는다. 이것이 없으면 §3 조정의 "조회: 있음 → CONFIRMED" 경로를 테스트할 수 없다.
Mock 인메모리 예약 저장소가 필요하다(재기동 시 사라져도 된다).

---

## 9. 테스트 (구현한다면)

| 층 | 무엇 |
|---|---|
| 단위 | 상태 머신 전이 전부. 허용되지 않은 전이(FAILED → CONFIRMED 등)는 예외 |
| 단위 | `retryable()` → UNKNOWN / FAILED 분기. `Skipped` → REJECTED |
| 컨텍스트 | 멱등 키 재전송 = 같은 응답, 다른 키 = 새 예약. 동시 재전송(2스레드)에서 UNIQUE 위반이 두 번째를 막는다 |
| 와이어 | 정상 생성·취소 / 타임아웃 → UNKNOWN → 조정기가 조회로 CONFIRMED / `created-but-no-response` → 조정 / 서킷 열림 → REJECTED 즉시 / 총액 불일치 → 409 + 자동 취소 |

---

## 10. 검토했다 버린 대안

| 대안 | 버린 이유 |
|---|---|
| 공급사 호출 뒤 INSERT | 공급사에 생겼는데 우리 DB에 없는 상태가 생긴다 — 조정할 실마리가 없다 |
| `UNKNOWN`을 `FAILED`로 | 고객에겐 "실패"인데 공급사엔 방이 잡혀 있다. 이 설계가 막으려는 사고 그 자체 |
| 타임아웃 시 create 즉시 재시도 | `clientReference`를 공급사가 안 받으면 중복 예약. 되돌리기가 가장 비싼 실패 |
| 별도 outbox 테이블 | 처리할 일이 예약 행의 상태에 다 있다. 테이블이 하나 더 생기면 두 곳을 맞춰야 한다 |
| 예약 API를 `SupplierAdapter`에 추가 | 예약을 지원하지 않는 공급사도 구현을 강요받는다. 별도 포트면 타입이 지원 여부를 말한다 |
| 검색 총액을 그대로 확정 | 검색과 예약 사이에 요금이 바뀐다. 확정 총액은 공급사 응답의 것이고 다르면 고객 동의가 필요하다 |
| Saga 프레임워크 | 참여자가 공급사 하나다. 상태 머신 + 조정기로 충분하고, 프레임워크는 설명할 수 없는 코드를 늘린다 |

---

## 11. 간단히 구현한다면 — 최소 범위

1. 포트 `SupplierBookingAdapter`(create · cancel · get) + A 어댑터만 구현, Mock A에 세 엔드포인트 + `created-but-no-response`
2. `reservation` feature: 엔티티(상태 머신) · 생성/취소 서비스 · 컨트롤러 · 멱등 키
3. 조정기는 스케줄러 대신 **관리 엔드포인트 `POST /admin/reservations/reconcile`** — 카탈로그 동기화와 같은 판단(주기는 설정 한 줄, 시점은 운영이 정한다)
4. 와이어 테스트 5개(§9)

이 정도가 하루다. 이 문서의 나머지(B 어댑터, 스케줄러, 다중 인스턴스 락, 요금 오차 정책)는 그 뒤의 일이다.

---

## 12. 남는 문제

- **다중 인스턴스** — 조정기 중복 실행. `SKIP LOCKED` 또는 리더 선출. 단일 인스턴스 가정을 여기서도 적는다.
- **요금 오차 허용** — 상품·통화 정책. 기본은 불일치 즉시 취소.
- **고객 정보(PII)** — 예약에는 이름·연락처가 따라온다. 저장 범위·암호화·보존 기간은 이 문서 범위 밖이고, 구현 전에 정해야 한다.
- **결제와의 순서** — 결제 후 예약인지 예약 후 결제인지. 결제는 이번 범위 밖이지만 상태 머신에 `PAYMENT_PENDING`이 끼어들 자리는 CONFIRMED 앞이다.
- **공급사별 취소 정책**(무료 취소 기한·수수료) — 표준 모델에 없다. 검색 응답의 `Offer`에 정책 요약을 싣는 것부터가 선행 작업이다.

---

## 미구현 항목

이 문서 전체가 미구현이다. 요구사항의 **선택 항목**("설계 또는 간단한 구현")이고, 설계로 답했다.

| 항목 | 이유 |
|---|---|
| 예약 생성·취소·조회 전체 | 공급사 스펙에 예약 API 계약이 없다. 계약이 없는 호출을 구현하면 §0의 가정을 코드로 굳히는 것이고, 실제 계약이 오면 전부 다시 써야 한다. 설계는 가정이 바뀌어도 §0만 갈아 끼우게 포트로 격리해 두었다 |
| Mock 예약 엔드포인트·`created-but-no-response` 모드 | 위와 같다. 어떤 모드가 필요한지는 §8에 적어 두었다 |
| `reservation` 테이블·상태 머신 | "DB에는 매핑만"의 첫 예외라 도입 근거(§7)를 설계에 남기는 것으로 충분했다 |
| 조정(reconcile) 스케줄러 | 앱의 첫 스케줄러가 되고 단일 인스턴스 가정이 하나 더 필요하다. 간단 구현으로 간다면 관리 엔드포인트가 대신한다(§11) |
| 요금 오차 허용 정책·PII 저장 범위·결제 순서 | 구현 전에 정해야 하는 정책이고 상품·법무 판단이다(§12) |

핵심 흐름(Mock → 카탈로그 → 어댑터 → 검색 → 부분 실패 → 서킷 → 지표)과 견고성 테스트가 끝난 뒤에도 시간이 남으면 §11의 최소 범위(하루)가 다음 후보다.
