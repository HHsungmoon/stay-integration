# 관측성 — 설계

작성일: 2026-09-05
상태: 구현 완료 (2026-09-05). §13·§14는 구현 후 추가

`common` + 병합 지점 두 곳. "공급사 A의 타임아웃 비율이 30%"를 말이 아니라 숫자로 보여 주는 단계다.

## 요약 — 왜 지금, 왜 거의 공짜인가

CLAUDE.md는 관측성을 "설계로 미루지 않고 구현한다"로 못 박았다. 비용이 거의 없기 때문이다 — `SupplierResult`가 이미 **공급사·소요시간·결과 분류**를
들고 있고, 05~07을 거치며 그 값이 흐르는 자리가 정해졌다. 검색은 `StaySearchService`가 결과를 합치는 한 줄, 동기화는 `CatalogSyncService.apply`가
결과를 순회하는 한 루프. 그 두 지점에서 `SupplierResult`를 한 번 더 보면 지표가 나온다. 새로 측정하는 것이 아니라 **이미 있는 값을 기록만 한다.**

서킷(07)의 상태·실패율은 Resilience4j가 이미 세고 있어 레지스트리를 Micrometer에 묶는 한 줄이면 나온다 — 07이 서킷을 낱개가 아니라
`CircuitBreakerRegistry`로 든 이유가 이것이었다.

**이 단계의 주장**: 응답에 실린 사실과 지표가 같은 곳에서 나온다. 검색 응답의 `failedCalls`·`unmapped`와 지표의 실패 수·미매핑 수가
**같은 순회에서 만들어지므로 둘이 어긋날 수 없다.**

---

## 0. 이 단계의 범위

| 만드는 것 / 고치는 것 | 위치 |
|---|---|
| `SupplierCallMetrics` — `SupplierResult` → Timer/Counter 기록. 태그 규약의 유일한 소유자 | `common` |
| `StaySearchService` — 병합 직후 `outcomes` 기록(한 줄). assembler는 순수 유지 | `search.service` |
| `CatalogSyncService.apply` — 결과 순회에서 기록(한 줄) | `catalog.service` |
| `SupplierCircuitMetricsConfig` — `MeterBinder` 빈으로 서킷 레지스트리를 Micrometer에 묶는다 | `supplier.adapter.support` |
| 의존성 `spring-boot-starter-actuator` · `resilience4j-micrometer`(명시 선언) | `build.gradle.kts` |
| yaml `management.endpoints.web.exposure.include: health,metrics` | `application.yaml` |
| README "확인 방법" 절 (§8) | `README.md` — 최종 갱신 때 |
| 단위 테스트 4 · 와이어 테스트 3 · ArchUnit 규칙 1 | `test` |

**바뀌지 않는 것**: 포트 · 어댑터 · `SearchResultAssembler`(순수 함수 그대로) · 응답 DTO · 상태 판정 규칙.

**하지 않는 것**: Prometheus 레지스트리·대시보드(§6) · Micrometer Tracing(리액티브 체인의 컨텍스트 전파는 별도 주제, 02 §3) · 구조화 로깅(→ **2026-09-06 별도 반영**: 컨테이너만 ECS JSON, 병합 지점 로그에 `supplier`·`kind` key-value — 02 §12) · 알람 임계값 · 관리 엔드포인트 인증(범위 밖, README에 명시) · 매핑 수 Gauge(§12).

---

## 1. 지표 목록

| 이름 | 타입 | 태그 | 기록 지점 | 답하는 질문 |
|---|---|---|---|---|
| `supplier.call` | **Timer** | `supplier` · `api`(catalog\|availability) · `outcome`(§2) | 병합 지점 — `Success`·`Failure`마다 `elapsed`로 | 공급사별 **성공률**(outcome=success 비율) · **지연 분포**(percentile) · **타임아웃 비율**(outcome=timeout 비율) |
| `supplier.call.skipped` | Counter | `supplier` · `api` | 병합 지점 — `Skipped`마다 | 서킷이 얼마나 자주 막았나. 카탈로그가 비어 부르지 않은 것도 여기 |
| `supplier.items.excluded` | Counter | `supplier` · `reason`(rejected\|unmapped) | 검색 병합 — `SupplierOutcome`의 두 카운터 | **unmapped > 0이면 동기화가 밀렸다**(D-10). rejected는 공급사 데이터 품질 |
| `search.result` | Counter | `status`(OK\|PARTIAL\|ALL_FAILED) | 검색 병합 — 응답 1건마다 | 고객이 본 부분 실패 비율. `http.server.requests`는 HTTP 200만 보여 이걸 모른다 |
| `resilience4j.circuitbreaker.*` | Gauge 등 | `name`(= supplier) · `state` … | Resilience4j `TaggedCircuitBreakerMetrics` | 지금 어느 공급사 서킷이 열려 있나. 실패율·느린 호출 비율 |

Boot가 공짜로 주는 것: `http.server.requests`(우리 API 지연·상태 코드 — `uri` 태그는 `/api/v1/stays/search`), `http.client.requests`(WebClient), JVM·HikariCP.
`http.client.requests`의 `uri` 태그는 **카탈로그 호출은 경로(`/a/v1/hotels`·`/b/api/properties`)가 찍히고 재고·요금 호출은 `none`이다**(2026-09-06 확인) —
전자는 `uri(String)`, 후자는 빌더 람다라 템플릿이 없다. `client.name`은 `mock-supplier`. 재고 호출을 경로별로 보고 싶으면 §12.
**`supplier.call`이 1차 지표고 `http.client.requests`는 보조다** — 전자만이 `outcome`을 우리 분류로 말한다.

`elapsed`는 `SupplierResult`의 값을 그대로 기록한다. Timer가 직접 재지 않는다 — 데드라인 보정으로 채운 `TIMEOUT`(06)은 실제 호출이 아니라
`elapsed = deadline`이 들어가고, 그것이 맞다: 고객이 그만큼 기다렸다.

---

## 2. `outcome` 태그 — 결정: B(2026-09-05)

CLAUDE.md 관측성 절은 `outcome`을 `success|timeout|supplier_error|auth_error|normalization_error` **5값**으로 적었다. 05에서 `FailureKind`가
8값으로 확정되면서 같은 문서의 실패 분류 절은 "`outcome` 태그는 `kind.name()`에서 파생된다"로 바뀌었다. 두 문장이 어긋나 있다.

| 선택 | 값 | 비용 |
|---|---|---|
| A. 5값 유지 | 8종 kind를 5값으로 접는 매핑 표 | 표 하나 더. `RATE_LIMITED`와 `SERVER_ERROR`가 `supplier_error`로 합쳐져 **429 비율을 못 본다** — 06 §13이 동시성 상한 근거로 삼기로 한 바로 그 숫자 |
| B. `kind.name()` 소문자 + `success` | 9값 | 매핑 표 없음. 카디널리티 9는 문제가 아니다 |

**→ B로 확정(2026-09-05).** CLAUDE.md 관측성 절을 같은 커밋에서 고쳤다.

**추천 B.** 05의 판단("재시도 여부만 말하는 3분류로는 로그와 지표에서 왜 실패했나를 읽을 수 없었다")을 지표에도 그대로 적용하는 것이다.
CLAUDE.md 관측성 절의 5값을 B로 고친다(규칙 17). `Skipped`는 `outcome` 값이 아니라 **별도 Counter**다(§3).

---

## 3. 어디서 기록하나

**병합 지점 두 곳, 기록 코드는 한 곳.**

```
StaySearchService.search
  fetcher.fetchAll(...)
      .map(outcomes -> {
          SearchResponse response = assembler.assemble(request, lookup, outcomes);
          supplierCallMetrics.recordSearch(outcomes, response);        ← 이벤트 루프 위. Micrometer는 무잠금·비블로킹
          return response;
      })

CatalogSyncService.sync
  for (result : fetchAll())            ← block 뒤, 톰캣·기동 스레드
      supplierCallMetrics.record(CATALOG, result);
      results.add(apply(result, now));
```

- **`SupplierCallMetrics`(common)가 태그 규약의 유일한 소유자다.** 두 호출 지점은 `SupplierResult`를 넘길 뿐 태그 이름을 모른다.
  이름·태그가 두 곳에 흩어지면 대시보드 쿼리가 한쪽만 맞는 사고가 난다.
- **assembler는 그대로 순수 함수다.** `MeterRegistry`를 주입하면 12개 단위 테스트가 레지스트리를 알아야 한다. 대신 service의 `.map` 안에서
  assembler의 출력(`SupplierOutcome`의 rejected·unmapped·status)과 입력(`outcomes`)을 함께 기록한다 — 06 §2가 "지표가 붙을 자리"라고 한 그 병합 지점이다.
  `.claude/rules/service.md`의 "(assembler)"를 "(service의 병합 지점)"으로 고친다.
- **어댑터 파이프라인에서 기록하지 않는다**(§10). 파이프라인은 50개 사전 거절(`BAD_REQUEST`), 데드라인 보정 `TIMEOUT`, fetcher의 계약 위반 `UNEXPECTED`를
  보지 못한다 — 그것들은 응답에는 있고 지표에는 없는 값이 된다. 병합 지점은 응답에 실리는 모든 결과를 본다.
- `Skipped`를 Timer에 넣지 않는 이유: `elapsed`가 0이라 지연 percentile을 아래로 끌어내린다. "호출하지 않음"은 지연이 없는 사건이다.

`SupplierCallMetrics`는 `MeterRegistry` 하나를 주입받고, Timer·Counter는 태그 조합마다 `registry.timer(name, tags)`로 얻는다(Micrometer가 캐시한다).

---

## 4. 서킷 지표 — `MeterBinder` 빈

```java
@Configuration
class SupplierCircuitMetricsConfig {
    @Bean
    MeterBinder supplierCircuitBreakerMetrics(SupplierCallPipelines supplierCallPipelines) {
        return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(supplierCallPipelines.registry());
    }
}
```

`MeterBinder` 빈은 Boot가 `MeterRegistry`에 자동으로 묶는다(**구현 시 Boot 4에서도 그런지 첫 번째로 확인** — §7). 생성자에서 `bindTo(meterRegistry)`를
직접 부르는 대신 빈으로 두는 이유: `SupplierCallPipelines`가 `MeterRegistry`를 알 필요가 없고, 레지스트리가 없는 컨텍스트(어댑터 단위 테스트)에서도 그대로 뜬다.

위치가 `supplier.adapter.support`인 이유: 서킷 레지스트리는 어댑터 인프라의 것이고, `common`이 `supplier.adapter..`를 알면 경계 2의 정신(병합 쪽은 어댑터를 모른다)이
흐려진다. Micrometer가 `common` 밖으로 새는 유일한 예외라 ArchUnit에 그렇게 적는다(§9).

지표 이름은 Resilience4j 기본(`resilience4j.circuitbreaker.state`·`.calls`·`.failure.rate` 등). 태그 `name`이 공급사 id다 — 07이 `circuitBreaker(supplierId.value())`로 만든 덕이다.

---

## 5. 태그 카디널리티 — 허용 목록

| 태그 | 값 | 개수 |
|---|---|---|
| `supplier` | 공급사 id | 공급사 수(2) |
| `api` | `catalog` / `availability` | 2 |
| `outcome` | `success` + 8 kind | 9 |
| `reason` | `rejected` / `unmapped` | 2 |
| `status` | `OK` / `PARTIAL` / `ALL_FAILED` | 3 |

**금지**: 숙소 코드·객실 코드(무한), `Skipped.reason`·`Failure.detail`(자유 텍스트 — "HTTP 503 upstream down: …"이 태그가 되면 값마다 시계열이 생긴다), URL, 날짜.
새 태그는 이 표에 먼저 적고 넣는다 — `SupplierCallMetrics`가 유일한 기록 지점이라 검사할 곳도 하나다.

---

## 6. Actuator 노출

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics
```

- `health`·`metrics`만. Boot 4 기본은 `health`뿐이라 `metrics`를 명시한다. `env`·`beans`는 열지 않는다 — 공급사 API 키가 `env`에 보인다.
- **Prometheus 레지스트리는 넣지 않는다.** 스크레이퍼가 없다. `/actuator/metrics/supplier.call?tag=supplier:a`로 값이 확인되고, 스크레이퍼가 생기면
  `micrometer-registry-prometheus` 한 줄(Boot BOM이 관리)과 `include`에 `prometheus` 추가로 끝난다 — 구현이 아니라 설정이라 그때 한다.
- **공급사 서킷 상태를 `health`에 넣지 않는다.** 한 공급사가 죽어 `ALL_FAILED`를 내는 것은 이 시스템의 **정상 동작**이다(CLAUDE.md 응답 형태). 그것을 `DOWN`으로
  보고하면 오케스트레이터가 멀쩡한 인스턴스를 재시작한다. 서킷 상태는 `resilience4j.circuitbreaker.state` 게이지로 본다.
- 관리 엔드포인트 인증은 범위 밖이다. README에 "운영이라면 `management.server.port`를 분리하거나 인증을 앞에 둔다"로 적는다.

---

## 7. 구현 전 확인 (규칙 11 — Boot 4는 모듈이 이동했다)

| 확인할 것 | 근거 |
|---|---|
| `spring-boot-starter-actuator`가 `MeterRegistry` 빈까지 주는가, 아니면 `spring-boot-starter-micrometer-metrics`가 따로 필요한가 | Boot 4.1.1 BOM에 둘 다 있다(확인). 4에서 `spring-boot-micrometer-metrics`로 쪼개졌으므로 actuator 스타터가 그걸 끌고 오는지 본다 |
| `MeterBinder` 빈 자동 바인딩 | Boot 3의 `MeterRegistryPostProcessor` 동작이 4에도 있는지 |
| **테스트에서 `MeterRegistry`가 실제로 기록하는가** | Boot 3은 `@SpringBootTest`가 지표 export를 끄고 no-op 합성 레지스트리를 준다 — `@AutoConfigureObservability`가 필요했다. 4에서는 `spring-boot-starter-micrometer-metrics-test`(BOM에 있다)의 애노테이션·패키지를 확인한다. **여기서 추측하면 와이어 테스트가 "count 0"으로 조용히 통과할 수 있다** |
| `TaggedCircuitBreakerMetrics` 지표 이름 | 07에서 클래스·메서드는 확인했고 이름은 `/actuator/metrics`에서 실제 값으로 확인한다 |
| `http.client.requests`의 `uri` 태그 값 | 빌더 람다 URI에서 무엇이 찍히는지 |

`resilience4j-micrometer`는 이미 `resilience4j-circuitbreaker`의 전이 의존으로 클래스패스에 있다(확인). **쓰는 것은 명시적으로 선언한다** — 전이 의존은
상위가 빼면 조용히 사라진다.

---

## 8. README에 적을 확인 방법

```
# 1. 둘 다 띄운다
./gradlew :mock-supplier:bootRun      # 9090
./gradlew :app:bootRun                # 8080 (PostgreSQL은 compose가 띄운다)

# 2. 검색 몇 번
curl 'localhost:8080/api/v1/stays/search?checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0'

# 3. B를 장애로 → 검색 → 지표
curl -X POST 'localhost:9090/control/b/mode?value=error&api=availability'    # Mock 제어 API (03)
curl 'localhost:8080/api/v1/stays/search?...'
curl 'localhost:8080/actuator/metrics/supplier.call?tag=supplier:b&tag=outcome:server_error'
curl 'localhost:8080/actuator/metrics/search.result?tag=status:PARTIAL'

# 4. 열 번 더 → 서킷이 열린다
curl 'localhost:8080/actuator/metrics/resilience4j.circuitbreaker.state?tag=name:b'
curl 'localhost:8080/actuator/metrics/supplier.call.skipped?tag=supplier:b'
```

되돌리기는 `POST localhost:9090/control/reset`. 서킷은 열림 10초 뒤 첫 검색에서 반열림으로 넘어가 세 번의 정상 응답으로 닫힌다(07 §6).

---

## 9. 테스트

| 층 | 검증 | 방법 |
|---|---|---|
| 단위 (`SimpleMeterRegistry`) | `Success` → Timer `outcome=success`, 기록된 시간 = `elapsed` | `SupplierCallMetricsTest` |
| 단위 | `Failure(kind)` → `outcome=kind 소문자`. 8종 전부 | 파라미터화 |
| 단위 | `Skipped` → Counter 증가, **Timer는 증가하지 않음** | percentile 오염 방지의 근거 |
| 단위 | `SupplierOutcome`의 rejected·unmapped → `supplier.items.excluded{reason}`, `status` → `search.result` | assembler 출력으로 기록 |
| 와이어 | B `error` 검색 후 `supplier.call{supplier=b, api=availability, outcome=server_error}` count 1, A는 `success` 1 | 06 클래스 — **§7의 테스트 레지스트리 확인이 선행** |
| 와이어 | 서킷 열림 → `supplier.call.skipped{supplier=b}` 증가, `resilience4j.circuitbreaker.state{name=b}` 게이지가 OPEN | 07 시나리오 재사용 |
| 와이어 | `GET /actuator/metrics/supplier.call?tag=supplier:b` 200 + `measurements` · `GET /actuator/health` UP · `GET /actuator/env` 404 | 노출 범위가 설정대로인지 |
| ArchUnit | Micrometer는 `..common..`과 `..supplier.adapter.support..`만 안다 | 태그 규약을 한 곳에 묶는 구조적 근거 |

---

## 10. 검토했다 버린 대안

| 대안 | 버린 이유 |
|---|---|
| 어댑터 파이프라인에서 기록 | 사전 거절·데드라인 보정·fetcher 안전망의 결과를 보지 못한다. 응답과 지표가 어긋난다 |
| assembler에 `MeterRegistry` 주입 | 순수 함수가 깨져 12개 단위 테스트가 레지스트리를 알아야 한다. service의 `.map` 한 줄이 같은 지점이다 |
| `Skipped`를 `outcome=skipped`로 Timer에 | `elapsed` 0이 지연 percentile을 왜곡한다. 지연이 없는 사건은 Counter다 |
| `outcome` 5값 유지(§2 A) | 429와 5xx가 합쳐져 동시성 상한의 근거(06 §13)를 잃는다 |
| `Failure.detail`·`Skipped.reason`을 태그로 | 자유 텍스트 — 값마다 시계열. 사유는 로그에 있다 |
| Prometheus 레지스트리 | 스크레이퍼가 없다. 설정 두 줄이라 필요할 때 한다 |
| 공급사 서킷 상태를 `health`에 | 부분 실패는 정상 동작이다. `DOWN`이면 멀쩡한 인스턴스가 재시작된다 |
| 매핑 수 Gauge | 스크레이프마다 DB 카운트 쿼리. 관리 API `GET /admin/catalog`가 이미 준다 |
| 지표를 로그에서 파생 | 로그 파이프라인이 없다. 지표는 지표로 |
| Micrometer Tracing | 리액티브 체인의 컨텍스트 전파가 별도 주제(02 §3). 지표가 먼저다 |

---

## 11. 갱신이 필요한 문서

| 문서 | 무엇 |
|---|---|
| `CLAUDE.md` 관측성 절 | `outcome` 5값 → `success` + `kind` 소문자(§2 B). `supplier.call.skipped`·`supplier.items.excluded`·`search.result` 추가 |
| `CLAUDE.md` Stack | Resilience4j `-micrometer` 명시 선언 |
| `.claude/rules/service.md` | "지표는 assembler 한 곳" → "service의 병합 지점 한 곳, 기록 코드는 `common.SupplierCallMetrics`" |
| `.claude/rules/testing.md` | 테스트에서 지표 레지스트리를 켜는 방법(§7에서 확인한 것) |
| `docs/07-circuit-breaker.md` §8 | "8단계에" → 구현됨 |
| `README.md`(최종) | §8 확인 방법, 관리 엔드포인트 인증 범위 밖 |

---

## 12. 남는 문제

- **알람 임계값** — "타임아웃 비율 30%"가 알람인지 정상인지는 트래픽을 봐야 안다. 지표를 먼저 쌓고 값은 나중에.
- **매핑 수 Gauge** — 동기화가 밀렸는지는 `unmapped` 카운터가 말하지만, "활성 매핑이 몇 개인지"는 관리 API로만 보인다. DB를 스크레이프마다 치지 않으려면
  동기화 결과를 메모리에 캐시한 Gauge가 필요하다 — 동기화 리포트에 이미 숫자가 있으니 비용은 작다. 필요해지면 그때.
- **관리 엔드포인트 보안** — 포트 분리 또는 인증. 범위 밖이지만 README에 적는다.
- **Tracing** — 검색 1건의 traceId가 두 공급사 호출까지 따라가면 사고 조사가 쉬워진다. Reactor 컨텍스트 전파가 관건이라 별도 단계.
- **`http.client.requests`의 `uri` 태그** — 빌더 람다라 템플릿이 없다. 필요하면 `uri(String template, Object... vars)` 형태로 바꿔 `/a/v1/availability`가 찍히게 한다.

---

## 13. 구현하면서 달라진 것

| 항목 | 설계(위) | 구현 | 이유 |
|---|---|---|---|
| §3 `recordSearch(outcomes, response)` | `SupplierCallMetrics`가 검색 타입을 받는다 | **원시 메서드 셋**: `record(Api, SupplierResult)` · `recordExcludedItems(SupplierId, ExclusionReason, int)` · `recordSearchResult(String)`. 순회는 `StaySearchService`가 한다 | `common`이 `search.service`·`search.dto`를 알면 의존 방향이 뒤집힌다. common은 포트 타입과 원시값만 안다 |
| §7 테스트 레지스트리 | Boot 4의 export 비활성 애노테이션을 확인 | **필요 없음** — 커스터마이저는 `spring-boot-micrometer-metrics-test`에 있고 그 모듈은 테스트 클래스패스에 없다(확인). `@SpringBootTest`의 `SimpleMeterRegistry`가 실제로 기록한다 | 와이어 테스트가 `count >= 1`로 이 사실을 못 박는다. 그 스타터를 넣으면 조용히 깨진다는 것을 `testing.md`에 적었다 |
| §7 `MeterBinder` 자동 바인딩 | 확인 항목 | Boot 4 `spring-boot-micrometer-metrics`에 `MeterRegistryPostProcessor`가 있다(확인) | — |
| §7 actuator 스타터 | `micrometer-metrics` 스타터가 따로 필요한지 | `spring-boot-starter-actuator`가 `spring-boot-micrometer-metrics`·`-observation`을 끌고 온다(확인) | 의존성 하나로 끝난다 |
| §9 와이어 "서킷 상태 게이지" | `resilience4j.circuitbreaker.state{name=b}` | 태그 `state=open`의 게이지 값 1 | r4j는 상태마다 게이지를 하나씩 두고 현재 상태만 1이다 |
| §9 미매핑 픽스처 | 언급 없음 | A-10023의 `FAM-STE`를 비활성화 | 객실이 하나뿐인 A-10044를 끄면 **숙소 자체가 조회 대상에서 빠져** 미매핑이 생기지 않는다(`CatalogLookupReader`의 규칙). 처음 그렇게 썼다가 `unmapped 0`으로 실패했다 |
| §7 `http.client.requests`의 `uri` | 확인 항목 | 카탈로그 호출은 경로, 재고·요금 호출은 `none`(컨테이너 스택에서 확인) | `uri(String)`과 빌더 람다의 차이. `supplier.call`이 1차 지표라 그대로 두고 §12에 남긴다 |

테스트 실제 수(app 165 = 07까지 150 + 15): `SupplierCallMetricsTest` 4개 메서드(kind 8종 파라미터화 → 실행 11) · `StaySearchWireTest` +3 · `ArchitectureTest` +1.
Mock 30 포함 전체 195, 컴파일 경고 0. 새 의존성은 `spring-boot-starter-actuator`와 `resilience4j-micrometer`(명시 선언) 둘.

## 14. 구현 기록 — 막힌 지점과 요약

**막힌 지점**

1. **미매핑 카운터가 0이었다.** A-10044의 유일한 객실을 비활성화했더니 미매핑이 아니라 "물어보지 않음"이 됐다 — 활성 객실이 없는 숙소는 코드 목록에서
   빠진다는 `CatalogLookupReader`의 규칙(04 §7) 때문이다. 객실이 셋인 A-10023의 하나를 끄는 것으로 바꿨다. 지표가 틀린 게 아니라 픽스처가 규칙을 잊은 것이고,
   그 규칙이 D-10과 정확히 맞물려 있음을 다시 확인한 지점이다: 미매핑은 "물어봤는데 모르는 것이 왔다"이고, 물어보지 않은 것은 미매핑이 아니다.
2. **테스트에서 지표가 기록되는지가 문서 단계의 가장 큰 불확실성이었다**(§7). Boot 3의 경험("`@SpringBootTest`는 export를 끈다")을 4에 그대로 적용하면
   틀렸을 것이다 — 4는 그 커스터마이저를 별도 모듈로 뺐고 우리는 그 모듈을 쓰지 않는다. jar 목록을 뒤져 확인한 뒤 코드를 썼다(규칙 11).

**요약**

- 코드 추가는 `SupplierCallMetrics` 한 클래스, `MeterBinder` 빈 하나, 병합 지점 두 곳의 호출, yaml 세 줄이다. 어댑터·포트·assembler·응답 DTO는 무변경 —
  "이미 있는 값을 기록만 한다"가 그대로 성립했다.
- `outcome`은 B(kind 소문자 9값). `Skipped`는 Counter. 자유 텍스트는 태그가 아니다 — 단위 테스트가 `reason` 태그 부재를 단언한다.
- 와이어 테스트가 응답의 `unmapped 1`과 지표의 `+1`, 응답의 `PARTIAL`과 `search.result{PARTIAL} +1`을 같은 요청에서 본다 — "같은 순회에서 나온다"의 증명.
- `/actuator/metrics/supplier.call?tag=supplier:a&tag=outcome:success`가 200으로 `measurements`를 주고, `/actuator/env`는 404다.
- 남은 단계: README·JOURNAL 최종 갱신(08 §8 확인 방법 포함), Docker Compose, GitHub Actions 재검토, `docs/` 추적 재개.

---

## 미구현 항목

| 항목 | 이유 |
|---|---|
| Prometheus 레지스트리·대시보드 | 스크레이퍼가 없다. `/actuator/metrics/{name}`으로 값이 확인되고, 붙일 때는 `micrometer-registry-prometheus` 한 줄 + `include`에 `prometheus`다(§6) |
| 알람 임계값 | "타임아웃 비율 30%"가 알람인지 정상인지는 트래픽을 봐야 안다. 지표를 먼저 쌓는다(§12) |
| Micrometer Tracing | 리액티브 체인의 컨텍스트 전파가 별도 주제(02 §3). 지표가 먼저다(§10) |
| 공급사 서킷 상태를 `health`에 | 부분 실패는 정상 동작이다. `DOWN`이면 오케스트레이터가 멀쩡한 인스턴스를 재시작한다. 상태는 `resilience4j.circuitbreaker.state`로(§6) |
| 활성 매핑 수 Gauge | 스크레이프마다 DB 카운트를 치게 된다. `GET /admin/catalog`가 이미 준다. 동기화 결과를 메모리에 캐시한 Gauge는 필요해지면(§12) |
| 관리·Actuator 엔드포인트 인증 | 범위 밖. 운영이라면 `management.server.port` 분리 또는 인증(§6, README) |
| 재고·요금 호출의 `http.client.requests` `uri` 태그 | 빌더 람다라 `none`이다. `supplier.call{api=availability}`가 같은 것을 우리 분류로 말하므로 `uri(String template, …)`로 바꾸는 일은 미뤘다 |
| 지표 export를 켠 테스트 검증 | 필요 없었다 — Boot 4는 export 비활성 커스터마이저를 `spring-boot-micrometer-metrics-test`에 두고 그 모듈이 없어 `@SpringBootTest`가 실제로 기록한다(§13). 그 스타터를 넣으면 와이어 테스트가 조용히 `count 0`으로 통과하므로 넣지 않는다 |
