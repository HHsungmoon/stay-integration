# 기술 선정 검토

작성일: 2026-09-03
최종 수정: 2026-09-06 (구현 결과 대조)
상태: 확정. 후보별 최종 결과는 §13, 안 한 것의 이유는 "미구현 항목"에.

---

## 0. 선정 기준

이 프로젝트에서 기술을 고를 때 두 가지를 동시에 만족해야 한다.

1. **최악의 상황을 가정하고 대응했음이 드러날 것.** 외부 연동이 주제이므로, 잘 도는 경우가 아니라
   공급사가 죽었을 때·느릴 때·거짓말할 때 무슨 일이 벌어지는지를 다룬 흔적이 남아야 한다.
   이 목적에 부합한다면 규모 대비 다소 과한 선택도 허용한다.
2. **내가 설명할 수 있을 것.** 의존성만 추가하면 동작하는 종류의 기술은, 원리를 모른 채 넣으면
   오히려 약점이 된다. 넣기로 한 것은 "언제 동작하고 언제 동작하지 않는지"를 말할 수 있어야 한다.

두 기준이 충돌하면 2번이 이긴다. 설명하지 못하는 코드는 저장소에 두지 않는다.

**등급 표기**

| 표기 | 의미 |
|---|---|
| ★ | 요구사항에 직접 대응. 넣는다 |
| ○ | 여유가 생기면 |
| △ | 비용 대비 효과가 애매하거나, 이 프로젝트에 자리가 없음 |

---

## 1. 확정된 스택 (이미 코드에 반영됨)

| 기술 | 역할 |
|---|---|
| Java 25 (LTS) | 요구 하한 21+ 충족. non-LTS 배제 |
| Spring Boot 4.1.1 | 안정 버전 중 최신 |
| Gradle, Kotlin DSL | 권장 사항 |
| Spring MVC + WebClient | 웹 계층은 블로킹, 외부 호출만 논블로킹 |
| PostgreSQL + JPA | 공급사 코드 ↔ 내부 식별자 매핑 저장 |
| Testcontainers | 테스트가 실제 PostgreSQL 컨테이너를 띄운다 |
| Lombok / Validation | 보일러플레이트 절감, 요청 검증 |
| 멀티모듈 (`:app`, `:mock-supplier`) | Mock을 별도 프로세스로 분리 |

---

## 2. 연동 견고성

이 프로젝트의 핵심 영역이다. 외부 연동이 주제이므로 여기가 가장 많이 읽힌다.

| 기술 | 무엇을 하나 | 어떤 최악 상황에 대응하나 | 이해해야 할 것 | 등급 |
|---|---|---|---|---|
| **Reactor 내장** (`timeout`, `retryWhen`, `flatMap(n)`) | 타임아웃·재시도·동시성 제한 | 공급사 지연, 일시적 5xx, 무제한 병렬로 인한 429 | 연산자가 체인의 **어느 지점**에 걸리는지. `timeout`을 개별 호출에 걸었나 병합된 스트림에 걸었나에 따라 의미가 완전히 다르다 | ★ (`timeout`·`take(deadline)`·`flatMap(n)` 구현. **`retryWhen`은 쓰지 않았다** — 데드라인 3s 안에 재시도 예산이 없다, `05` §13) |
| **Resilience4j** | 서킷 브레이커 | 한 공급사가 계속 죽어 있는데 매 검색마다 붙잡고 기다리는 상황 | 닫힘 → 열림 → 반열림 전이 조건, 슬라이딩 윈도우, WebClient 체인의 어느 지점에 적용되는지 | ★ |
| 벌크헤드 (Resilience4j 또는 스레드풀 격리) | 공급사별 자원 격리 | 공급사 A의 장애가 B 호출까지 굶기는 상황 | Reactor의 `flatMap` 동시성 인자로 상당 부분 대체 가능함 | ○ → **안 함.** 공급사당 `flatMap(…, 4)`가 그 자리(`06` §4) |

**재시도에 대한 주의** — 재시도를 켜면 최악의 소요 시간이 `응답 타임아웃 × 시도 횟수`가 되어
전체 데드라인(3초)을 넘길 수 있다. 재시도 총 예산이 데드라인 안에 들어오도록 제한해야 한다.

**Resilience4j — 확정 (2026-09-05)**. 처음엔 "Boot 4 호환이 30분 내에 해결될 때만"이라는 조건부였다.
확인 결과:

| 확인 | 결과 |
|---|---|
| Boot 4.1.1 BOM이 관리하는가 | 아니오 — 버전을 직접 지정한다 |
| 최신 버전 | **2.3.0** |
| `resilience4j-spring-boot3` (스타터) | Boot 3 자동설정에 묶여 있다 → **쓰지 않는다** |
| `resilience4j-circuitbreaker` · `-reactor` · `-micrometer` (코어) | Boot 버전과 무관 → **이것을 쓴다** |

스타터를 버린 것이 오히려 낫다. `@CircuitBreaker` 어노테이션 마법이 없어져서 **언제 열리고 닫히는지가
코드에 드러나고**, Reactor 체인에 `transformDeferred(CircuitBreakerOperator.of(cb))`로 명시적으로 붙는다.
"설명할 수 없는 코드를 남기지 않는다"는 규칙에 부합한다.

**공급사별로 서킷을 따로 둔다.** 하나로 묶으면 A의 장애가 B를 차단해 부분 실패 허용을 스스로 무너뜨린다.
서킷이 열려 호출조차 하지 않은 경우는 `SupplierResult`의 제3 케이스(`Skipped`)로 표현한다 —
sealed interface라 이 케이스를 처리하지 않은 코드는 컴파일 에러로 드러난다.

---

## 3. 관측성

"최악을 가정했다"를 가장 적은 비용으로 보여줄 수 있는 영역이다.

| 기술 | 무엇을 하나 | 이해해야 할 것 | 등급 |
|---|---|---|---|
| **Actuator** | `/actuator/health`, `/actuator/metrics` 노출 | 어떤 엔드포인트를 열고 닫을지. 기본값은 대부분 닫혀 있다 | ★ |
| **Micrometer** | 공급사별 성공률·지연·타임아웃 비율 기록 | Counter / Timer / Gauge의 차이, 태그(차원)를 어떻게 잡을지 | ★ |
| **구조화 로깅** (Boot 4 내장) | 로그를 JSON으로 출력 | 설정 한 줄. 라이브러리 추가 불필요 | ★ |
| Micrometer Tracing + OpenTelemetry | 검색 요청 1건에 traceId를 붙여 공급사 호출까지 추적 | 컨텍스트가 스레드를 넘어 전파되는 방식. **리액티브 체인에서는 이게 까다롭다** | ○ |
| Prometheus + Grafana (compose에 추가) | 메트릭 대시보드 | 인프라가 둘 늘어난다. README 스크린샷 효과는 크다 | ○ |

메트릭은 사실상 공짜로 나온다. 각 호출 결과를 성공/실패 값 타입(`SupplierResult`)으로 감싸는 구조라면,
그 값이 이미 공급사·소요시간·결과 분류를 들고 있기 때문이다.

---

## 4. 설계 규칙을 기계가 강제하게 만들기

문서에 적은 규칙이 코드에서 실제로 지켜지는지를 사람 눈이 아니라 도구가 검사하게 한다.

| 기술 | 무엇을 하나 | 등급 |
|---|---|---|
| **ArchUnit** | "공급사 DTO는 어댑터 패키지 밖으로 나갈 수 없다"를 테스트로 강제. 위반하면 빌드가 깨진다 | ★ |
| **sealed interface + record** | 성공/실패를 타입으로 못 박는다. 실패 케이스 처리를 빠뜨리면 컴파일이 안 된다 | ★ |

요구사항이 "설계 문서에 적은 내용이 실제 코드에 반영되어야 한다"고 정하므로,
이 두 가지는 그 요구에 가장 직접적으로 답하는 수단이다.

---

## 5. 테스트

| 기술 | 무엇을 하나 | 등급 |
|---|---|---|
| ~~MockWebServer / WireMock~~ → **Spring `ExchangeFunction` 스텁** | 테스트 안에서 공급사 응답을 스텁. Spring이 제공하므로 의존성 없음. 실제 타임아웃·연결 거부는 Mock 모듈로 | ★ |
| Testcontainers | 확정. 실제 PostgreSQL | ✅ |
| Awaitility | 비동기 결과를 `Thread.sleep` 없이 대기 | ○ |
| JaCoCo | 커버리지 리포트 | ○ |

`:mock-supplier` 모듈과 역할이 다르다는 점을 구분해야 한다.

- `:mock-supplier` — 사람이 띄워서 눈으로 확인하는 가짜 서버 (필수 구현 항목)
- MockWebServer — 자동화 테스트 안에서만 살아 있는 스텁

둘은 경쟁 관계가 아니라 용도가 다르다.

---

## 6. DB 스키마

| 기술 | 무엇을 하나 | 등급 |
|---|---|---|
| Flyway | 스키마를 SQL 파일로 버전 관리 | △ **도입하지 않음** |
| Liquibase | Flyway 대안. XML/YAML 기반 | △ |
| **엔티티 유니크 제약 선언** | `@Table(uniqueConstraints = ...)`로 불변 조건을 DB에 강제 | ★ |

**판단이 한 번 뒤집혔다.** 처음에는 Flyway를 넣고 `ddl-auto: update`를 피하려 했다.
"운영에서 쓰지 않는 설정"이라는 일반론이 근거였다.

CLAUDE.md에서 도입하지 않는 것으로 확정했다 — 테이블 2개 규모에서 **마이그레이션 이력 자체가
이 저장소의 산출물이 아니다.** 이 저장소가 보여줘야 할 것은 스키마 버전 관리 능력이 아니라 연동 설계다.

대신 불변 조건의 강제 지점을 엔티티 애노테이션에 둔다. Hibernate가 이 선언을 보고 제약을 만들며,
**애노테이션에 없으면 DB에도 제약이 없다.** 실운영이라면 Flyway + `ddl-auto=validate`로
스키마 단일 소유자를 두는 것이 맞고, 그 판단도 README에 남긴다.

---

## 7. 문서화

| 기술 | 무엇을 하나 | 등급 |
|---|---|---|
| springdoc-openapi **3.x** | 어노테이션에서 API 문서 생성, 브라우저에서 바로 호출. **2.x는 Boot 3용이라 동작하지 않는다** | ○ → **구현**(3.1.0, 2026-09-06). 어노테이션 없이 컨트롤러 시그니처·응답 record에서 생성, 테스트로 고정 |
| Spring REST Docs | 테스트가 통과해야 문서가 나온다. 정확하지만 손이 많이 감 | △ |

---

## 8. 빌드·CI

| 기술 | 무엇을 하나 | 등급 |
|---|---|---|
| **GitHub Actions** | push마다 빌드+테스트. README 배지 | ★ → **미구현.** 마감 단계까지 도달하지 못했다. 클린 클론 빌드는 로컬로 확인(2026-09-06) |
| Version Catalog (`libs.versions.toml`) | 의존성 버전을 한 파일로 모음 | ○ |
| Spotless / Checkstyle | 포맷 자동화 | ○ |

CI는 Testcontainers를 쓰므로 러너에서 Docker가 동작하는지 확인이 필요하다
(GitHub 호스티드 Ubuntu 러너는 기본 제공).

---

## 9. 검토했으나 넣지 않을 것

| 기술 | 사유 |
|---|---|
| Flyway | 테이블 2개 규모에서 마이그레이션 이력이 이 저장소의 산출물이 아니다. 불변 조건은 엔티티의 유니크 제약 선언으로 강제한다 (§6) |
| MapStruct | 공급사 응답 → 표준 모델 변환이 이 프로젝트의 **핵심 판단**이다. 자동 생성에 맡기면 정작 설명해야 할 부분이 코드에서 사라진다 |
| Redis (캐시) | 캐시는 선택 구현이고, 인프라가 하나 더 늘어난다. 설계 문서로 남긴다 |
| Kafka / 이벤트 스트리밍 | 이 시스템의 흐름(동기 검색 요청 1건)에 등장할 자리가 없다 |
| WebFlux 전면 도입 | 요구되지 않는다. 검색 외 흐름은 JPA 기반이라 블로킹 스택이 자연스럽다 |

---

## 10. 채택 후보 정리

**넣는다** — Reactor 연산자, **Resilience4j(코어 모듈)**, Actuator + Micrometer, 구조화 로깅,
ArchUnit, sealed interface + record, GitHub Actions. 공급사 응답 스텁은 라이브러리 없이 Spring `ExchangeFunction`으로 (2026-09-05 — MockWebServer 5.x가 alpha이고 BOM 밖)

이 조합의 공통점은 **문서에 쓴 주장을 코드나 도구가 증명해준다는 것**이다.

**여유가 생기면** — Micrometer Tracing, springdoc-openapi, Prometheus + Grafana,
Version Catalog, Awaitility, JaCoCo

→ 결과(2026-09-06): "넣는다" 중 **GitHub Actions만 미구현**, 나머지는 전부 코드에 있다. "여유가 생기면" 중 **springdoc만 구현**했다. 상세는 §13.

---

## 11. 이해 부담이 큰 항목 (선정 기준 2번 관점)

아래 두 가지는 "붙여만 두고 원리를 모르는" 상태가 되기 쉽다. 넣기로 했다면 시간을 따로 써야 한다.

| 항목 | 왜 위험한가 | 최소한 답할 수 있어야 하는 질문 |
|---|---|---|
| **Resilience4j** | 어노테이션 하나로 동작해서, 내부 상태 전이를 모른 채 쓰기 쉽다 | 서킷이 열리는 조건은? 열린 동안 요청은 어떻게 되나? 언제 다시 닫히나? 실패율은 무엇을 기준으로 세나? → 답은 `07` §2·§3·§6. 어노테이션 대신 `CircuitBreakerOperator`를 명시적으로 붙였고, 값 기반 실패를 `recordResult`로 세는 함정을 바이트코드로 확인했다 |
| **Micrometer Tracing** | 컨텍스트 전파가 리액티브 체인에서 특히 까다롭다 | traceId가 WebClient 호출까지 따라가는 원리는? 스레드가 바뀌어도 유지되는 이유는? → **넣지 않았다**(`08` 미구현 항목). 이 질문에 답할 시간을 따로 쓰지 않았다 |

---

## 12. 남은 결정 → 전부 해결됨 (2026-09-05)

| 결정 | 결과 | 어디 |
|---|---|---|
| 실패 분류를 enum 하나로 둘지, sealed interface로 쪼갤지 | **enum 하나(`FailureKind` 8값) + `retryable()`.** 케이스별로 다른 데이터가 없었고, 결과 컨테이너 `SupplierResult`가 이미 sealed(Success·Failure·Skipped)라 분류까지 sealed로 쪼개면 두 겹이 된다. 처음 3분류(재시도 여부)로 갔다가 "로그와 지표에서 왜 실패했나를 읽을 수 없다"로 8값으로 바꿨다 | `docs/05` §14, 커밋 `bc902e1` |
| 패키지·좌표 정리 | **`com.demo.stayintegration`**. Initializr 기본값 `com.test`를 저장소에 굳히지 않기 위해 어댑터 구현 전에 옮겼다 | 커밋 `bc66256` |
| 공급사 A·B의 요청/응답 형태 정의 | **Mock 문서(`docs/03`)가 계약이다.** 스펙 예시 데이터를 그대로 쓰되 Mock 코드는 우리 구현물이고, 본체의 공급사 DTO는 어댑터 패키지 밖으로 나가지 않는다(ArchUnit 경계 1) | `docs/03` §4, `docs/05` §3 |

이 절에서 "넣는다"로 적은 것 중 **구조화 로깅**은 08 단계까지 들어가지 않았다. 결정이 아니라 누락이었고, 지표(08)가 관측 수단을 먼저 가져갔다.
**2026-09-06 반영**: 컨테이너만 ECS JSON(`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, compose 환경 변수), 병합 지점의 로그에 `supplier`·`event`·`kind`·`retryable` key-value.
수집기 없이 로컬 콘솔을 JSON으로 바꾸면 가독성만 잃어 둘을 나눴다. Boot 4.1.1 ECS 포매터가 SLF4J 2 key-value를 필드로 내보내는 것을 바이트코드와 실제 컨테이너 로그로 확인했다.

---

## 13. 후보별 최종 결과 (2026-09-06 대조)

| 후보 | 이 문서의 등급 | 결과 | 어디 |
|---|---|---|---|
| Reactor `timeout` · `flatMap(n)` | ★ | 구현 — 응답 타임아웃 두 겹, 공급사당 동시 4, `take(deadline)` | `05` §5.2, `06` §4 |
| Reactor `retryWhen` | ★(같은 행) | **안 함** — 데드라인 안에 예산이 없다 | `05` §13 |
| Resilience4j 서킷 | ★(조건부 → 확정) | 구현 — 코어 모듈, 공급사당 하나, `recordResult` = `retryable()` | `07` |
| 벌크헤드 | ○ | 안 함 — `flatMap` 동시성 인자가 대신 | `06` §4 |
| Actuator + Micrometer | ★ | 구현 — `health`·`metrics`, `supplier.call` 등 4지표 + 서킷 지표 | `08` |
| 구조화 로깅 | ★ | 구현(누락됐다가 9/6) — 컨테이너만 ECS JSON, key-value | §12, `08` |
| Micrometer Tracing | ○ | 안 함 | `08` 미구현 항목 |
| Prometheus + Grafana | ○ | 안 함 — 스크레이퍼 없음 | `08` §6 |
| ArchUnit | ★ | 구현 — 경계 7규칙 | `06` §9, `07`, `08` |
| sealed interface + record | ★ | 구현 — `SupplierResult`·`CallOutcome`·Mock `AvailabilityQuery` | `04` §3.3 |
| `ExchangeFunction` 스텁 (MockWebServer 대체) | ★ | 구현 — `StubExchange` | `05` §10 |
| Testcontainers | ✅ | 구현 | `04` §9 |
| Awaitility · JaCoCo | ○ | 안 함 — 필요가 드러나지 않았다 | — |
| Flyway · Liquibase | △ | 안 함 — 유니크 제약을 엔티티 선언 + 실제 DB 테스트로 | §6, `04` |
| springdoc 3.x | ○ | **구현**(3.1.0) | README |
| Spring REST Docs | △ | 안 함 | — |
| GitHub Actions | ★ | **미구현** | — |
| Version Catalog · Spotless/Checkstyle | ○ | 안 함 — 런타임 동작이 달라지지 않는다 | — |
| MapStruct · Redis · Kafka · WebFlux 전면 | (§9) | 안 함 | §9 |

## 미구현 항목

| 항목 | 이유 |
|---|---|
| **GitHub Actions** | "넣는다"로 적었으나 마감 단계까지 도달하지 못했다. 클린 클론에서 `./gradlew build` 199개 통과를 로컬로 확인했다(`JOURNAL` 9/6). 러너에서 Testcontainers용 Docker가 도는지(호스티드 Ubuntu는 기본 제공) 확인 뒤 워크플로 하나면 된다 — 남은 작업 |
| Reactor `retryWhen` | 응답 2s × 2회 = 4s > 데드라인 3s. 응답 타임아웃을 줄이지 않으면 재시도 자체가 불가능하다(`05` §13) |
| 벌크헤드 | 공급사당 `flatMap(…, 4)`가 부하 상한을 맡는다. 서킷과 층이 겹쳐 셋째 층은 과하다(`07` §12) |
| Micrometer Tracing | 리액티브 체인의 컨텍스트 전파가 별도 주제. 지표가 먼저였고 §11의 질문에 답할 시간을 따로 쓰지 않았다 |
| Prometheus + Grafana | 스크레이퍼가 없다. 붙일 때는 의존성 한 줄 + `include`(`08` §6) |
| Awaitility · JaCoCo · Version Catalog · Spotless | 런타임 동작이 달라지지 않는다(규칙 12). 비동기 대기는 `block(timeout)`으로 충분했다 |
| Flyway · Liquibase | 테이블 2개에서 마이그레이션 이력이 산출물이 아니다(§6). 실운영이라면 Flyway + `validate` |
| Spring REST Docs | springdoc으로 충분했다. 테스트 통과가 문서 생성의 전제가 되는 비용을 낼 이유가 없었다 |
| MapStruct · Redis · Kafka · WebFlux 전면 도입 | §9 |
