# 서킷 브레이커 — 설계

작성일: 2026-09-05
상태: 구현 완료 (2026-09-05). §13·§14는 구현 후 추가

`supplier.adapter.support`. 이미 아는 실패에 시간을 쓰지 않게 만드는 단계다.

## 요약 — 타임아웃이 있는데 왜 서킷인가

타임아웃은 **한 번의 호출**을 끊는다. 죽은 공급사에 매 검색마다 2초씩 태우는 것은 막지 못한다.
검색 100건이 들어오면 100번 2초를 기다리고, 그동안 이벤트 루프와 커넥션은 이미 답이 정해진 호출에 묶여 있다.
서킷은 "직전 호출들이 계속 실패했다"는 사실을 **다음 호출의 입력으로 쓴다** — 그래서 호출을 아예 하지 않는다.

CLAUDE.md는 서킷을 선택 항목에서 빼 확정으로 올렸다(2026-09-05). "최악의 상황을 가정하고 대응했음"을 가장 직접적으로 보여주는 장치이고,
순서를 견고성 통합 테스트 **뒤**로 둔 이유는 `Skipped` 케이스가 추가될 때 기존 코드가 컴파일 에러로 드러나야 하기 때문이다.
06이 끝난 지금 그 조건이 갖춰졌다 — `SupplierResult`의 세 케이스를 다루는 곳(카탈로그 동기화·검색 병합)이 모두 존재한다.

**이 단계의 주장**: 서킷이 열려 호출하지 않은 것은 "이 공급사 호출의 결과"라 어댑터가 `Skipped`로 표현하고,
**병합·판정 로직은 그대로 쓴다** — 검색 응답의 상태 규칙도, 동기화 리포트도 손대지 않는다. 이 사실이 깨지면 경계가 잘못 그어진 것이다.
다만 "요청 도중에 서킷이 열리는" 경우가 새로 생기므로 카운터 한 필드가 늘어난다(§5). 그것이 `search`에 생기는 변경의 전부다.

---

## 0. 이 단계의 범위

| 만드는 것 / 고치는 것 | 위치 |
|---|---|
| `SupplierCallPipelines` — `CircuitBreakerRegistry`를 들고 공급사별로 설정된 파이프라인을 발급하는 팩토리 | `supplier.adapter.support` |
| `SupplierProperties.Circuit` — 임계값·대기시간 설정 | `supplier.adapter.support` |
| `SupplierCallPipeline` — `CircuitBreakerOperator` + `CallNotPermittedException` → `Skipped` (§1) | `supplier.adapter.support` |
| `SupplierOutcome.skippedCalls` — 요청 도중 열린 서킷 때문에 건너뛴 청크 수 (§5) | `search.dto.response` |
| yaml `supplier.circuit.*` | `application.yaml` |
| 단위 테스트 7 · 와이어 테스트 3 · ArchUnit 규칙 1 | `test` |

**바뀌지 않는 것**: 포트(`SupplierResult.Skipped`는 처음부터 있다) · `catalog` 전체 · A·B 어댑터의 호출·정규화 본문 ·
검색의 상태 판정 규칙(§5) · `StaySearchService`·`SupplierAvailabilityFetcher`.

**어댑터가 바뀌는 곳은 생성자 한 줄**이다. 지금 A·B는 각자 `new SupplierCallPipeline(ID, 타임아웃)`을 만드는데, 여기에 서킷까지
직접 조립하게 두면 같은 코드가 두 벌 생기고 신규 공급사마다 세 벌이 된다. `SupplierWebClients.forSupplier(id)`와 같은 모양의
`SupplierCallPipelines.forSupplier(id)`로 옮기면 어댑터의 협력자 수는 지금과 같은 둘이고, 새 공급사는 서킷을 자동으로 얻는다.

**하지 않는 것**: 재시도(버리는 순서 1위 — 데드라인 3s 안에 예산이 없다) · bulkhead(동시성 상한을 `search`가 이미 갖는다) ·
Micrometer 바인딩(8단계, §8) · 서킷 상태 조회·강제 개폐 관리 API(범위 밖).

---

## 1. 붙는 자리 — 그리고 기본 설정 그대로 붙이면 안 되는 이유

서킷은 **어댑터 파이프라인의 맨 바깥**이다. 정규화보다, 타임아웃보다 바깥이어야 "호출하지 않음"이 표현된다.

```
SupplierCallPipeline.execute
  ├ 권한 거부 → CallNotPermittedException → SupplierResult.Skipped("circuit open")   ← HTTP 호출 없음
  └ 권한 획득 → 소요시간 측정 → 응답 타임아웃 → 판정 → SupplierResult → 서킷이 그 값을 보고 기록
```

```
return Mono.defer(() -> { ...기존 체인... })                  // 실패는 여기서 이미 값이 된다
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
        .onErrorResume(CallNotPermittedException.class,
                e -> Mono.just(new SupplierResult.Skipped<>(supplierId, "circuit open", Duration.ZERO)));
```

**함정은 오퍼레이터가 아니라 기본 설정이다.**

어댑터 계약이 "예외를 던지지 않는다"이므로 실패는 `Mono.error`가 아니라 **정상 emission인 `SupplierResult.Failure` 값**이다.
서킷의 기본 판정은 "에러 신호 = 실패"라, 설정을 그대로 두면 **모든 실패를 성공으로 기록하고 서킷은 영원히 열리지 않는다.**
컴파일도 되고, 실패를 세지 않으니 테스트도 조용히 통과한다 — "서킷을 구현했다"는 문장만 남는 종류의 버그다.

답은 `CircuitBreakerConfig.recordResult(Predicate<Object>)`다. 방출된 **값**으로 실패를 판정하는 술어이고,
2.3.0의 `CircuitBreakerSubscriber`는 Mono(단일 생산자)일 때 값을 `CircuitBreaker.onResult(duration, unit, value)`로 넘겨
이 술어를 태운다(바이트코드로 확인). 술어가 참이면 상태 머신이 `ResultRecordedAsFailureException`으로 실패를 기록한다.

```java
CircuitBreakerConfig.custom()
    .recordResult(result -> result instanceof SupplierResult.Failure<?> failure && failure.kind().retryable())
    ...
```

즉 **우리가 쓰는 것은 술어 하나**이고, 권한 획득·반열림 허용 수·취소 시 권한 반환은 라이브러리가 이미 맞게 한다(§7).
직접 `tryAcquirePermission()`·`onSuccess()`·`onError()`를 부르는 수동 기록도 가능하지만, 그러면 저 셋을 우리가 다시 구현하는 것이라 쓰지 않는다(§10).

파이프라인이 이 자리를 맡는 이유: **결과를 `SupplierResult`로 만드는 유일한 지점**이다. 서킷을 어댑터 본문에 두면 A·B에 같은 코드가
두 벌 생기고, `search`에 두면 카탈로그 동기화 경로가 보호받지 못한다.

---

## 2. 무엇을 실패로 세는가

**기록 여부 = `FailureKind.retryable()`.** 새 술어를 만들지 않는다.

| `FailureKind` | 서킷 기록 | 왜 |
|---|---|---|
| `TIMEOUT` · `CONNECTION_FAILED` · `SERVER_ERROR` · `RATE_LIMITED` | **실패로 기록** | 공급사 쪽 문제다. 계속 부르면 그쪽 부하를 키우고 우리 시간을 태운다 |
| `BAD_REQUEST` | 무시 | 51개를 보낸 것은 **우리 버그**다. 우리 버그로 정상 공급사를 차단하면 장애를 우리가 만든다 |
| `UNAUTHORIZED` | 무시 | 401은 즉답이라 비용이 거의 없고, 서킷 뒤에 숨기면 응답의 원인이 "circuit open"으로 바뀌어 **키 설정 오류라는 진짜 원인이 사라진다** |
| `UNEXPECTED` | 무시 | **모르는 것으로 서킷을 열지 않는다** — 재시도 규칙과 같은 원칙이다. 이해하지 못한 실패로 멀쩡한 공급사를 끊을 수 있다 |
| `NORMALIZATION_FAILED` | 무시 | 공급사는 건강하다. 계약이 바뀐 것이고, 답은 차단이 아니라 어댑터 수정이다 |

두 판단(재시도해도 되는가 / 서킷을 열 만한가)이 같은 질문 — **"공급사 쪽 문제인가, 우리 쪽이거나 모르는 문제인가"** — 이라서
술어가 하나로 충분하다. 갈리는 날이 오면 그때 나눈다. 지금 나누면 같은 내용의 표를 두 벌 관리하게 된다.

**50개 초과 사전 거절은 애초에 세지 않는다.** 어댑터가 파이프라인을 타지 않고 바로 `Failure(BAD_REQUEST)`를 돌려주므로
권한을 얻지도, 기록하지도 않는다 — 호출하지 않은 것을 호출 통계에 넣지 않는 것이 맞다.

---

## 3. 상태 전이와 값

```yaml
supplier:
  circuit:
    sliding-window-size: 20              # 호출 수 기준
    minimum-number-of-calls: 10          # 이 전에는 판정하지 않는다
    failure-rate-threshold: 50           # %
    wait-duration-in-open-state: 10s
    permitted-number-of-calls-in-half-open-state: 3
```

키 이름은 `CircuitBreakerConfig.Builder`의 메서드 이름과 1:1로 맞춘다(`slidingWindowSize` · `minimumNumberOfCalls` ·
`failureRateThreshold` · `waitDurationInOpenState` · `permittedNumberOfCallsInHalfOpenState`). 우리 record가 받는 값이지만
이름이 다르면 읽는 사람이 매핑을 다시 확인해야 한다.

| 값 | 근거 |
|---|---|
| **호출 수 기반**(시간 기반 아님) | 시간 창은 트래픽이 없을 때 비어 판정이 흔들리고, 테스트가 시계를 다뤄야 한다. 호출 수는 결정적이라 테스트가 `transitionToHalfOpenState()` 없이도 재현된다 |
| 창 20 / 최소 10 | 검색 1건이 공급사당 청크 1호출이므로 10은 "**여러 번의 검색**"이지 한 번의 사고가 아니다. 5로 두면 순간적인 네트워크 흔들림에 열린다 |
| 50% | 절반이 실패하는 공급사는 이미 검색 품질을 망친다. 더 높이면 열릴 때쯤엔 이미 사용자가 다 겪은 뒤다 |
| 열림 유지 10s | 검색 데드라인 3s의 3배 이상. 짧으면 죽은 공급사를 계속 두드리고, 길면 복구를 늦게 알아챈다. 사람이 새로고침하는 간격이기도 하다 |
| 반열림 허용 3 | 1이면 그 한 번이 우연히 실패했을 때 다시 10초를 닫는다. 3이면 한 번의 검색(청크 여럿) 안에서 판정이 난다 |
| 자동 전이 **끄기** | `automaticTransitionFromOpenToHalfOpenEnabled`를 켜면 열림 상태가 공유 스케줄러에 전이 작업을 예약한다(`SchedulerFactory`). 끄면 예약하지 않고, **다음 호출이 대기시간 경과를 확인해** 반열림으로 넘긴다 — 호출이 없으면 회복 여부를 알아낼 이유도 없다 |
| 느린 호출 임계값 **미사용** | 응답 타임아웃 2s가 이미 느린 호출을 실패로 바꾼다. 두 장치가 같은 것을 세면 임계값이 서로를 가려 어느 쪽이 열었는지 설명할 수 없다 |

`maxWaitDurationInHalfOpenState`(반열림에 머무는 시간 상한)는 두지 않는다 — 기본값 0(무제한)이다. 반열림에 갇히는 경로는
취소인데 그것을 라이브러리가 이미 막는다(§7). 그 가정이 깨지는 것을 보게 되면 그때 넣는다.

설정은 **공급사 공통**으로 둔다. 공급사별 오버라이드는 지금 근거가 없다 — 공급사마다 다른 값을 줄 이유가 생기면 그때 `endpoints` 아래로 내린다.

---

## 4. 서킷 하나의 범위 — 공급사당인가, API당인가

**공급사당 하나**(카탈로그·재고 두 API가 공유).

- 지배적인 실패(연결 거부·타임아웃·인증 오류)는 **호스트 수준**이다. 두 API는 같은 호스트·같은 키·같은 커넥션 풀을 쓴다.
- 카탈로그는 기동 시 1회 + 관리자 호출뿐이라 **자기 창(최소 10호출)을 영원히 채우지 못한다.** 열릴 수 없는 서킷은 죽은 코드다.
- 검색 트래픽으로 열린 서킷이 관리자 동기화를 `Skipped`로 만드는 것은 부작용이 아니라 **맞는 동작**이다 — 그 공급사는 지금 아프다.
  기존 매핑은 그대로 남고(D-8), 리포트에 `SKIPPED`로 드러난다.

대안(API당 하나)은 "재고 경로만 죽은" 경우를 구분하지만, 그 구분이 지금 주는 이득이 없고 상태가 두 배가 된다. §11에 남긴다.

---

## 5. 열렸을 때 응답이 어떻게 보이나

**검색** — `SearchResultAssembler`가 이미 `Skipped`를 처리한다(06 §3).

```json
{ "supplier": "b", "status": "SKIPPED", "calls": 0, "failedCalls": 0, "skippedCalls": 1, "elapsedMs": 0,
  "offers": 0, "rejected": 0, "unmapped": 0,
  "failure": { "kind": "SKIPPED", "retryable": false, "detail": "circuit open" } }
```

- `calls: 0` — 호출하지 않았으므로 성공에도 실패에도 세지 않는다. A만 살아 있으면 전체 `status`는 `OK`이고 items는 A 것만 나온다.
- **전 공급사가 서킷으로 `SKIPPED`면 `OK` + 빈 items + `no-store`**다. 06에서 정한 그대로 — 실패 사실은 `status`가 아니라
  `suppliers[]`가 말하고, 빈 결과가 캐시되지 않는 것은 `no-store`가 보장한다. (이 판단은 §11에 재검토 항목으로 남긴다.)
- 청크가 여럿이면 청크마다 `Skipped`가 하나씩 나온다. 어댑터가 서킷을 아는 유일한 주체라 fetcher가 미리 걸러내지 않는다 —
  비용은 청크당 boolean 확인 한 번이고, `calls`는 여전히 0이다.

**요청 도중에 열리는 경우 — 지금 병합 코드에 구멍이 있다.**

동시성 상한이 4이므로 청크가 20개인 공급사는 앞 4개가 실패한 뒤 서킷이 열리고, **나머지 16개가 `Skipped`로 돌아온다.**
06의 `SearchResultAssembler`는 `Skipped`를 "호출하지 않음"으로만 다뤄 `calls`에도 `failedCalls`에도 넣지 않고,
`SupplierOutcome.status`는 `calls > 0`이라 `FAILED`가 된다. 그 자체는 맞지만 **16개가 응답에서 흔적 없이 사라진다** —
`offers`가 왜 적은지 설명할 값이 없다.

→ **`SupplierOutcome`에 `skippedCalls`를 추가한다.** assembler가 `Skipped`를 세기만 하면 되는 3줄 변경이고,
상태 판정 규칙(§3의 표)은 건드리지 않는다. `calls + skippedCalls`가 그 공급사에 대해 계획했던 청크 수가 되어
`offers`가 적은 이유가 응답 안에서 설명된다. 06의 `failedCalls`를 정직하게 만든 것과 같은 판단이다.

숙소가 3개인 지금은 청크가 1개라 이 경로가 **아직 도달 불가능**하다. 그래도 넣는 이유는, 청크가 늘어나는 순간
조용히 정보가 사라지는 종류의 버그이고 비용이 카운터 하나이기 때문이다. 단위 테스트로 못 박는다(§9).

**카탈로그 동기화** — `CatalogSyncService`가 이미 `Skipped`를 `SupplierSyncResult.skipped`로 리포트한다. 기존 매핑은 손대지 않는다.

---

## 6. 회복

```
CLOSED ──실패율 50% 도달──> OPEN ──10s 경과 후 첫 호출──> HALF_OPEN ──3호출 중 실패 0~1건──> CLOSED
                                                             └──3호출 중 실패 2건 이상──> OPEN(다시 10s)
```

**반열림도 "한 번 실패하면 다시 열림"이 아니다.** 허용된 3호출이 모두 기록된 뒤에야 판정하고, 기준은 닫힘 상태와 같은
실패율 50%다 — 반열림 통계는 창 크기와 최소 호출 수를 **허용 호출 수(3)로** 잡기 때문이다(`CircuitBreakerMetrics.forHalfOpen`,
`minimumNumberOfCalls = min(3, 10)`). 그래서 3건 중 1건 실패(33%)는 닫히고, 2건 실패(66%)여야 다시 열린다.
회복 판정이 한 번의 우연에 좌우되지 않는다는 뜻이라 §3에서 허용 수를 3으로 둔 이유와 같은 이야기다.

반열림에서 실패 판정은 §2의 표를 그대로 쓴다. 반열림 중 통과한 호출은 **실제 호출**이므로 그 사용자는 최대 2초를 기다린다 —
회복을 확인할 다른 방법이 없다(별도 헬스체크 호출은 공급사 계약에 없다). §11에 남긴다.

상태 전이는 **INFO 로그**로 남긴다(`onStateTransition`). 호출마다 찍지 않는다 — 전이는 드물고, 그 순간이 사고 조사의 시작점이다.

---

## 7. 스레드·취소 — 라이브러리가 이미 맞게 하는 것들

06의 `take(deadline)`는 데드라인을 넘긴 청크를 **실제로 취소한다.** 반열림 상태에서 허용 호출 3개가 취소로 소진되면
서킷은 성공도 실패도 기록하지 못한 채 반열림에 갇힌다 — 우리가 직접 짰다면 그 처리를 잊었을 지점이다.

`CircuitBreakerSubscriber.hookOnCancel`은 **아직 아무 값도 방출하지 않았으면 `releasePermission()`**을,
값을 이미 방출했으면 `onSuccess()`를 부른다(바이트코드 확인). 우리가 할 일은 없다. 다만 이 경로가 실제로 존재하므로
**우리 조합에서 그렇게 동작하는지는 테스트로 못 박는다**(§9) — 라이브러리 동작에 기대는 부분일수록 근거가 필요하다.

- 권한 획득은 `MonoCircuitBreaker.subscribe`가 **구독 시점**에 한다. 따라서 `transform`을 써도 권한은 구독마다 새로 얻는다 —
  `transformDeferred`를 쓰는 것은 공식 문서가 쓰는 형태이고, 나중에 서킷을 레지스트리에서 지연 조회하도록 바꿔도 안전해서다.
  둘 중 무엇을 써도 권한 획득 시점은 같다.
- 서킷의 상태 갱신은 무잠금 원자 연산이라 이벤트 루프를 막지 않는다. 별도 스레드도 만들지 않는다(§3 자동 전이 끄기).

---

## 8. 관측성은 8단계로

8단계의 바인딩은 `TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry)` 한 줄이다 —
**레지스트리 하나만 넘기면 모든 공급사의 서킷 지표가 한 번에 붙는다.** `SupplierCallPipelines`가 서킷을 낱개로 들지 않고
`CircuitBreakerRegistry`로 들어야 하는 이유가 이것이다(§0). `MeterRegistry` 빈은 Actuator와 함께 8단계에 들어오므로
`resilience4j-micrometer` 의존성과 바인딩을 그때 함께 추가한다. 지금은 상태 전이 로그가 관측 수단이다.

> **08에서 구현됨(2026-09-05).** `SupplierCircuitMetricsConfig`가 `MeterBinder` 빈으로 레지스트리를 묶는다. `resilience4j.circuitbreaker.state{name=b, state=open}` 게이지를 와이어 테스트가 본다.

이 단계에 추가되는 의존성은 `resilience4j-circuitbreaker`와 `resilience4j-reactor` **둘**이다(각 2.3.0 — Boot 4 BOM이 관리하지 않아 버전을 직접 적는다).
CLAUDE.md가 적어 둔 코어 모듈 셋 중 `-micrometer`만 8단계로 미룬다.

---

## 9. 테스트

| 층 | 검증 | 방법 |
|---|---|---|
| 단위 | 임계 도달 후 **호출하지 않는다** | `StubExchange`가 503을 반복 → 임계 뒤 결과가 `Skipped`, **`StubExchange.requests` 수가 더 늘지 않는 것**으로 증명. "호출 안 함"은 결과 타입이 아니라 요청 수로 증명해야 한다 |
| 단위 | 무시되는 실패는 열지 않는다 | 401을 6번(창 4·최소 3을 넘긴다) → 계속 `Failure(UNAUTHORIZED)`, 서킷은 `CLOSED` |
| 단위 | 50개 초과 사전 거절은 통계에 들어가지 않는다 | 51코드 요청 6번 → `CLOSED` 유지, 요청 수 0, 버퍼된 호출 0 |
| 단위 | 반열림 성공 → 닫힘 | `transitionToHalfOpenState()`로 결정적으로 만든 뒤 성공 3회. **실패 1회 + 성공 2회로도 닫히는 것**을 같이 본다(§6) |
| 단위 | 반열림 실패 → 다시 열림 | 동일, 3호출 중 실패 2회 |
| 단위 | **취소가 권한을 반환한다**(라이브러리 동작이지만 우리 조합에서 확인) | 반열림에서 구독 후 값 방출 전 취소 ×3 → 이후 호출이 여전히 허용됨(반열림에 갇히지 않음) |
| 단위 | 요청 도중 열린 서킷이 응답에 남는다 | assembler에 `Success` 4 + `Failure` 4 + `Skipped` 16을 넣고 `calls` 8 · `failedCalls` 4 · `skippedCalls` 16 · `status` `PARTIAL`(§5) |
| 와이어 | 열림이 검색 응답에 드러난다 | Mock을 `error`로 두고 검색 반복 → `SKIPPED` + `detail: circuit open` + `elapsedMs` 0에 가까움. 다른 공급사 items는 살아 있음 |
| 와이어 | 회복 | Mock 정상 복귀 + 대기시간 경과 → 다음 검색이 `SUCCESS` |
| 와이어 | 열린 서킷에서 동기화 | 카탈로그 동기화 리포트가 `SKIPPED`, 매핑 행 수 불변(D-8) |
| ArchUnit | 서킷은 어댑터의 내부 사정 | `..search..`·`..catalog..`는 `io.github.resilience4j..`에 의존하지 않는다 (경계 2의 연장) |

와이어 테스트는 06의 `StaySearchWireTest`에 추가하고, 설정을 테스트에서 줄인다(`minimum-number-of-calls: 2`, `wait-duration: 200ms`).
운영값(10 / 10s)으로 테스트하면 스무 번 호출하고 10초를 기다려야 한다 — **설정을 yaml로 뺀 이유의 절반이 이것**이다.

---

## 10. 검토했다 버린 대안

| 대안 | 버린 이유 |
|---|---|
| 기본 설정 그대로 `CircuitBreakerOperator` | 값으로 돌려주는 실패를 전부 성공으로 기록해 서킷이 영원히 열리지 않는다. `recordResult` 술어가 반드시 있어야 한다(§1) |
| 수동 기록(`tryAcquirePermission`·`onSuccess`·`onError` 직접 호출) | 값 기반 판정을 우리가 통제한다는 이점이 있으나, 권한 획득·반열림 허용 수·**취소 시 권한 반환**까지 우리가 다시 구현해야 한다. 술어 하나로 끝나는 일에 상태 관리를 떠안는다 |
| 실패를 잠시 예외로 되돌렸다가 다시 값으로 | 값 ↔ 예외 왕복이 파이프라인에 남고 이름이 하는 일과 어긋난다(규칙 14). `recordResult`가 있으므로 애초에 필요 없다 |
| `resilience4j-spring-boot3` 스타터 | Boot 3 자동설정에 묶여 있다. 어노테이션 마법 대신 체인에 명시적으로 붙이는 편이 동작이 드러난다(CLAUDE.md) |
| 손으로 짠 서킷(카운터 + 마지막 실패 시각) | 슬라이딩 윈도우·반열림 허용 수·동시성까지 다시 만들어야 한다. **런타임 동작이 손으로 짠 것과 다른** 경우라 규칙 12의 도입 근거가 성립한다 |
| 서킷을 `search`(fetcher)에 두기 | "호출하지 않음"을 어댑터 밖에서 만들게 되고, 카탈로그 동기화 경로는 보호받지 못한다. 신규 공급사가 서킷을 자동으로 얻지도 못한다 |
| 서킷 기록 대상을 별도 술어로 | `retryable()`과 같은 질문이다. 표를 두 벌 관리하면 어긋난다 — 갈리는 날 나눈다 |
| 전 공급사 `SKIPPED`를 `ALL_FAILED`로 | 서킷은 **보호 동작**이라 장애로 세지 않기로 한 06의 판단과 모순된다 |
| 부분 `Skipped`를 세지 않고 두기 | 청크 16개가 응답에서 조용히 사라진다(§5). 카운터 하나로 막을 수 있는 종류의 침묵이다 |
| 서킷 + 재시도 함께 | 재시도는 버리는 순서 1위다. 데드라인 3s 안에서 재시도 예산을 만들면 서킷이 열리기 전에 부하를 두 배로 만든다 |

---

## 11. 갱신이 필요한 문서

| 문서 | 무엇 |
|---|---|
| `CLAUDE.md` Stack | Resilience4j 모듈 중 `-micrometer`는 8단계에 추가한다는 것(목록 자체는 그대로) |
| `.claude/rules/adapter.md` | 서킷의 위치, 기록 대상(`retryable()`), **`recordResult` 없이는 서킷이 열리지 않는다는 것** |
| `.claude/rules/testing.md` | "호출하지 않음"은 요청 수로 증명한다는 한 줄 |
| `docs/05-adapter.md` | 파이프라인 절에 서킷이 맨 바깥에 붙었다는 각주 |
| `docs/06-search.md` §1.2 · §10 | 응답에 `skippedCalls` 추가(§5), 와이어 테스트에 서킷 시나리오 3개 추가 |

---

## 12. 남는 문제

- **다중 인스턴스** — 서킷 상태는 인스턴스별이다. 인스턴스 3대면 각자 자기 실패로 학습하고, 열리는 시점이 어긋난다.
  공유하려면 외부 저장소가 필요한데 그 조회가 새로운 외부 의존이 된다. 단일 인스턴스 가정을 README에 적는다.
- **API당 서킷** — §4의 대안. 재고 경로만 죽은 경우를 구분하려면 필요하다. 8단계 지표에서 두 API의 실패율이 갈리는 것이 보이면 그때 나눈다.
- **전 공급사 `SKIPPED`의 전체 status** — 지금은 `OK` + 빈 items다. "판매할 게 없다"와 "전부 아프다"를 `status` 한 값으로 구분하고 싶다면
  `ALL_SKIPPED` 같은 값이 필요한데, 클라이언트의 판정 로직을 늘리는 값이라 미룬다.
- **반열림 중 사용자 지연** — 회복 확인 호출을 실제 사용자 요청이 대신 낸다. 별도 헬스체크 API가 공급사 계약에 없어 지금은 답이 없다.
- **서킷과 동시성 상한** — `search`의 공급사당 4는 부하 상한이고 서킷은 실패 대응이라 층이 다르다. bulkhead를 따로 두면 세 층이 되어 과하다.

---

## 13. 구현하면서 달라진 것

| 항목 | 설계(위) | 구현 | 이유 |
|---|---|---|---|
| §3 설정 기본값 | 언급 없음 | **기본값 없음 — yaml 필수, 누락은 기동 실패** | `Endpoint`와 같은 판단: 임계값은 설계값이라 yaml에 근거와 함께 드러나 있어야 한다. `supplier.circuit`이 없으면 `SupplierProperties` 생성자가 즉시 던진다 |
| §8 `registry()` 노출 | 8단계에 | **지금 노출** | 테스트가 상태 조회·`reset()`에 쓴다. 낱개 `CircuitBreaker` 대신 레지스트리인 이유는 그대로(8단계 지표 바인딩) |
| §9 단위 테스트 위치 | 파이프라인 | **실제 어댑터 A + `StubExchange`** (`SupplierCircuitBreakerTest`) | "호출하지 않음"을 HTTP 요청 수로 증명하려면 어댑터를 통과해야 한다. 50개 사전 거절 경로도 어댑터에만 있다 |
| §9 와이어 "호출 안 함" 증명 | `elapsedMs` 0에 가까움 | 서킷을 연 뒤 Mock을 `no-response`로 바꾸고 **500ms 안에 `SKIPPED`** | Mock에 호출 카운터가 없다. 호출했다면 700ms 타임아웃에 걸렸을 모드에서 즉시 끝나는 것이 더 강한 증명이다 |
| 기존 어댑터 테스트 | 언급 없음 | `StubExchange.CIRCUIT_THAT_NEVER_OPENS`(창 1,000) | A·B 어댑터 테스트는 서킷을 모르는 채 그대로 통과해야 한다. 서킷은 별도 테스트가 작은 창으로 본다 |

테스트 실제 수(app 150 = 06까지 138 + 12): `SupplierCircuitBreakerTest` 6 · `SearchResultAssemblerTest` +1(부분 Skip) · `SupplierPropertiesTest` +1 ·
`StaySearchWireTest` +3 · `ArchitectureTest` +1. Mock 30 포함 전체 180, 컴파일 경고 0. `search`의 변경은 `SupplierOutcome.skippedCalls` 필드와
assembler의 카운트 3줄이 전부이고, `catalog`는 무변경이다 — §0의 주장이 코드로 확인됐다.

## 14. 구현 기록 — 막힌 지점과 요약

**막힌 지점**

1. **와이어 테스트에서 서킷이 예상보다 한 호출 먼저 열렸다.** `minimum-number-of-calls: 2`로 두고 "검색 2회 실패 → 열림"을 기대했는데,
   `@BeforeEach`의 카탈로그 동기화가 이미 성공 1건을 **같은 창에** 넣어 두어 첫 실패 뒤 2건(50%)으로 열렸다. 서킷이 공급사당 하나(§4)라
   카탈로그 호출도 같은 통계에 들어간다는 결정이 테스트에서 그대로 드러난 것이다. 동기화 뒤에 `reset()`을 한 번 더 한다.
2. **테스트용 작은 설정에서 반열림 판정 시점이 달라진다.** 반열림 통계의 최소 호출 수는 `min(허용 수, minimumNumberOfCalls)`다(§6).
   단위 테스트를 최소 2로 두면 허용 3 중 2건만으로 판정되어 "1건 실패는 닫힘"이 성립하지 않는다. 최소 호출 수를 허용 수 이상(3)으로 맞췄다.
   운영값(10 / 3)은 처음부터 이 조건을 만족한다.
3. **문서 단계에서 두 번 틀렸다.** "`CircuitBreakerOperator`를 못 쓴다"(값 기반 실패를 안 센다)와 "반열림은 1회 실패로 다시 열린다"는
   기억에 의존한 문장이었고, 2.3.0 바이트코드를 읽어 둘 다 뒤집었다(규칙 11). 구현은 뒤집힌 쪽을 따랐고 테스트가 그것을 못 박는다 —
   `recordResult` 술어 하나로 서킷이 열리고(`repeatedRetryableFailuresOpenTheCircuit…`), 3건 중 1건 실패는 닫힌다(`halfOpenCloses…`).

**요약**

- 서킷은 `SupplierCallPipeline.execute()`의 맨 바깥에 `transformDeferred(CircuitBreakerOperator)` + `onErrorResume(CallNotPermittedException → Skipped)` 두 줄로 붙었다.
  핵심은 오퍼레이터가 아니라 `SupplierCallPipelines`의 `recordResult(result -> Failure && retryable())` 한 줄이다 — 이것이 없으면 서킷은 켜져 있으되 영원히 닫혀 있다.
- 어댑터 생성자가 `SupplierProperties` 대신 `SupplierCallPipelines`를 받는다. 신규 공급사는 `forSupplier(id)` 한 줄로 타임아웃과 서킷을 함께 얻는다.
- 와이어 테스트가 §4의 결정을 그대로 보여 준다: 검색 실패로 열린 b의 서킷이 카탈로그 동기화를 `SKIPPED`로 만들고 매핑은 그대로다.
- 취소 시 권한 반환은 라이브러리가 하지만 우리 조합에서 테스트로 확인했다(`cancellingBeforeAnyResultReleasesTheHalfOpenPermit`).
- 다음(8단계): `TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(supplierCallPipelines.registry()).bindTo(meterRegistry)` 한 줄과 `supplier.call` Timer.

---

## 미구현 항목

| 항목 | 이유 |
|---|---|
| API당(카탈로그 / 재고) 서킷 | 카탈로그는 기동 1회 + 관리자 호출뿐이라 자기 창(최소 10호출)을 영원히 못 채운다 — 열릴 수 없는 서킷은 죽은 코드. 재고 경로만 죽는 일이 지표(08)에서 보이면 그때 나눈다(§4) |
| 다중 인스턴스 간 서킷 상태 공유 | 상태는 인스턴스 메모리다. 공유하려면 외부 저장소가 새 외부 의존이 된다. 단일 인스턴스 가정을 README에 적었다(§12) |
| 전 공급사 `SKIPPED`용 status 값 | 지금은 `OK` + 빈 items + `no-store`. 실패 사실은 `suppliers[]`가 말한다. 클라이언트 판정 로직을 늘리는 값이라 미뤘다(§5·§12) |
| `maxWaitDurationInHalfOpenState` | 반열림에 갇히는 경로는 취소인데 라이브러리가 권한을 반환하고 테스트로 확인했다(§7). 가정이 깨지는 것을 보면 그때 |
| 반열림 확인용 별도 헬스체크 호출 | 공급사 계약에 헬스체크 API가 없다. 회복 확인은 실제 사용자 요청이 대신 낸다(§6·§12) |
| bulkhead | `search`의 공급사당 동시성 상한이 부하 상한이고 서킷은 실패 대응이라 층이 다르다. 셋째 층은 과하다(§12) |
| 서킷 상태 조회·강제 개폐 관리 API | 범위 밖. 상태는 `resilience4j.circuitbreaker.state` 지표(08)로 본다 |
| 재시도 | 버리는 순서 1위. 데드라인 3s 안에 예산이 없고, 서킷이 열리기 전에 부하만 두 배가 된다(§10) |
