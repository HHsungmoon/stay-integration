# 실행·검증 안내

설계 근거는 `README.md`에, 단계별 상세는 `docs/`에 있다. 이 문서는 **돌려 보고 확인하는 데 필요한 것만** 모았다.

## 준비물

| 필요 | 이유 |
|---|---|
| **Docker Desktop 실행 중** (필수) | PostgreSQL 컨테이너, Testcontainers(테스트), 전체 스택 실행 |
| JDK 25 | Gradle로 직접 빌드·테스트할 때만. Docker 경로(아래 1번)는 JDK 없이 된다 |

첫 실행은 이미지·의존성 다운로드로 몇 분 걸린다. 그 뒤로는 빌드 20초 안팎, 테스트 1분 안팎이다.

---

## 1. 가장 빠른 길 — Docker로 전부 띄우기

```bash
docker compose --profile stack up --build        # postgres + Mock 공급사(9090) + 본체(8080). 이미지 안에서 빌드한다
```

`app-1 | catalog sync: b=SUCCESS(+3 …), a=SUCCESS(+6 …)` 로그가 보이면 준비된 것이다. 본체가 뜨면서 카탈로그 동기화를 1회 하고, 매핑 숙소 3 · 객실 6이 생긴다.

```bash
curl 'localhost:8080/api/v1/stays/search?checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0'
```

`"status": "OK"`와 `items` 6건이 오면 정상이다. 내리기는 `docker compose --profile stack down`.
API 문서는 `localhost:8080/swagger-ui/index.html`(springdoc, 코드에서 자동 생성). 컨테이너 로그는 JSON(ECS)이다 — `docker compose --profile stack logs --no-log-prefix app | grep '"event":"call_failed"'`로 공급사 실패 줄만 볼 수 있다(`supplier`·`kind` 필드).

호스트 8080이 이미 쓰이고 있으면 `APP_PORT=18080 docker compose --profile stack up --build`(아래 curl의 8080도 그 포트로).

## 2. Gradle로 직접 띄우기 (JDK 25)

터미널 둘. PostgreSQL은 `bootRun`이 `compose.yaml`에서 자동으로 띄운다.

```bash
./gradlew :mock-supplier:bootRun     # 9090
./gradlew :app:bootRun               # 8080
```

**1번과 2번을 동시에 띄우지 않는다** — 8080·9090이 충돌한다. 컨테이너 스택이 떠 있으면 먼저 `down`.

---

## 3. 테스트

```bash
./gradlew build                      # 컴파일 + 테스트 199개 (app 168 · mock-supplier 31). Docker 필요
./gradlew :app:test --tests '*StaySearchWireTest'      # 한 클래스만
```

결과 리포트: `app/build/reports/tests/test/index.html`.

**무엇을 증명하는 테스트인지** — 이 넷을 보면 된다.

| 클래스 | 증명하는 것 |
|---|---|
| `search/StaySearchWireTest` (13) | 동기화 → 검색 전 구간을 실제 HTTP로. Mock 모드를 바꿔 **정상 병합 / A 무응답 → PARTIAL, 3초 안 / B 200+E503 → SERVER_ERROR / 전부 실패 → HTTP 200 + ALL_FAILED + no-store / 미매핑 카운트 / 서킷 열림·회복·동기화 건너뜀 / 응답과 지표 일치 / Actuator 노출 범위** |
| `supplier/adapter/SupplierCircuitBreakerTest` (6) | 실패 반복 뒤 **HTTP 요청 수가 늘지 않는다**, 401·51개 초과는 서킷을 열지 않는다, 반열림 판정, 취소 시 권한 반환 |
| `catalog/CatalogSyncServiceTest` (10) | 동기화 3회 반복해도 내부 ID 불변, 사라진 상품 재등장 시 같은 ID, 한 공급사 실패가 다른 공급사를 막지 않음, 동시 실행 409 |
| `ArchitectureTest` (7) | 아키텍처 경계 7규칙(ArchUnit) — 공급사 DTO 격리, 포트만 의존, 계층 단방향 등 |
| `search/SearchScaleWireTest` (2) | 공급사당 숙소 120·298개를 Mock에 붙여 **50개 청크가 실제 HTTP로 흐르는지**, 파동이 데드라인을 넘으면 도착한 청크만 살고 나머지가 `TIMEOUT`인지 |

---

## 4. 직접 눌러 보기 — 견고성 시나리오

1번 또는 2번으로 띄운 상태에서. Mock 모드 전환은 `POST localhost:9090/control/{a|b}/mode?value={normal|error|no-response|delay}&api={catalog|availability}`, 되돌리기는 `POST localhost:9090/control/reset`, 현재 모드는 `GET localhost:9090/control`.

```bash
S='localhost:8080/api/v1/stays/search?checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0'
```

| # | 상황 | 명령 | 기대 결과 |
|---|---|---|---|
| 1 | 정상 | `curl $S` | `status OK`, `items` 6건. 같은 호텔이 a·b에 있어도 내부 숙소 ID가 다르다. A 상품에는 `priceDetail`(날짜별)이 있고 B는 `null` |
| 2 | B 장애 — HTTP 200에 본문 코드로 실패 | `curl -X POST 'localhost:9090/control/b/mode?value=error&api=availability'` 후 `curl -i $S` | `PARTIAL`, `suppliers[b].failure.kind = SERVER_ERROR`, `detail "E503 …"`, items는 A 것 4건, 헤더 `Cache-Control: no-store`. **HTTP는 200** |
| 3 | A 무응답 | `curl -X POST 'localhost:9090/control/a/mode?value=no-response&api=availability'` 후 `time curl $S` | 약 2초 뒤 `PARTIAL`, `suppliers[a].failure.kind = TIMEOUT`, B 것 2건. 3초 안에 끝난다 |
| 4 | 전부 실패 | a·b 둘 다 `value=error` 후 `curl -i $S` | `ALL_FAILED`, items 없음, **HTTP 200** + `no-store` |
| 5 | 서킷 열림 | B를 `error`로 두고 `curl $S`를 **10번 이상** | 어느 시점부터 `suppliers[b].status = SKIPPED`, `failure.detail "circuit open"`, `calls 0`. B를 `no-response`로 바꿔도 즉시 응답한다 — 호출하지 않는다는 증명 |
| 6 | 서킷 회복 | `reset` 후 **10초** 기다리고 `curl $S` 세 번 | 첫 호출이 반열림으로 넘기고(그래도 `SUCCESS`), 셋이 성공하면 닫힌다 |
| 7 | 요청 검증 | `curl -i 'localhost:8080/api/v1/stays/search?checkIn=2026-09-04&checkOut=2026-09-01&adults=2&children=0'` | `400`, `code INVALID_DATE_RANGE`. `adults=0` → `INVALID_PARAMETER`, 31박 → `TOO_MANY_NIGHTS`, 파라미터 누락 → `MISSING_PARAMETER` |
| 8 | 재고 판정 | 1번 응답에서 `Standard Double`(Namsan) | `availableRooms 0`, `available false` — 사흘 중 하루가 0이라 연박 불가. 응답에서 빼지 않는다 |
| 9 | 카탈로그 동기화 실패 | `curl -X POST 'localhost:9090/control/b/mode?value=error&api=catalog'` 후 `curl -X POST localhost:8080/admin/catalog/sync` | 리포트에 `b FAILED SERVER_ERROR`, `a SUCCESS`. `curl localhost:8080/admin/catalog`의 매핑 수는 그대로(기존 매핑 유지) |
| 10 | 지표 | `curl 'localhost:8080/actuator/metrics/supplier.call?tag=supplier:b&tag=outcome:server_error'` | 2·4·5번에서 쌓인 호출 수. `…/supplier.call.skipped?tag=supplier:b`는 서킷이 막은 수, `…/resilience4j.circuitbreaker.state?tag=name:b&tag=state:open`은 열려 있으면 1 |
| 11 | 규모 — 50개 청크 | `curl -X POST 'localhost:9090/control/catalog/synthetic?count=298'` → `curl -X POST localhost:8080/admin/catalog/sync` → `curl $S` | 공급사당 숙소 300개(예시 + 합성 298) → `suppliers[].calls 6`(50개 청크 6개), items 600여 건, 수백 ms. 이어서 A를 `value=delay&delayMs=1800&api=availability`로 두고 다시 검색하면 동시 4 → 첫 파동 4청크(1.8s)만 살고 둘째 파동 2청크는 **3초 데드라인에 잘려 `TIMEOUT`**(`failedCalls 2`, `PARTIAL`) — 도착분으로 응답한다. `reset`이 합성 숙소도 지운다(동기화를 다시 돌리면 비활성화됨) |

끝나면 `curl -X POST localhost:9090/control/reset`.

---

## 5. 읽는 순서

| 무엇 | 어디 |
|---|---|
| 설계 의사결정과 근거, 구현하지 않은 것 | `README.md` |
| 단계별 설계 → 검토했다 버린 대안 → 구현하면서 달라진 것·막힌 지점 | `docs/01`~`08` (요구사항 해석 → 기술 선정 → Mock → 카탈로그 → 어댑터 → 검색 → 서킷 → 관측성) |
| 일자별 진행, 뒤집은 판단, AI 활용 기록 | `JOURNAL.md` |
| 개발 지침·확정 판단(D-1~D-13)·경계 | `CLAUDE.md`, `.claude/rules/` |
| 왜 그렇게 정했는지 | `git log` — PR 없이 커밋 제목이 판단 근거를 말한다 |

---

## 문제가 생기면

- **테스트가 DataSource 오류로 실패** → Docker Desktop이 꺼져 있다. Testcontainers가 PostgreSQL을 띄워야 한다.
- **`ports are not available: 8080`** → 다른 프로세스가 쓰고 있다. `APP_PORT=18080`으로 덮거나 그 프로세스를 내린다.
- **검색이 전부 `CONNECTION_FAILED`** → Mock(9090)이 안 떠 있다. 이것은 버그가 아니라 설계된 동작(`ALL_FAILED`, HTTP 200)이다.
- **서킷이 열린 뒤 B가 계속 `SKIPPED`** → 열림 유지 10초. `reset` 뒤 10초 기다리면 다음 호출부터 반열림으로 넘어간다.
- **첫 `docker compose up --build`가 오래 걸림** → 이미지 안에서 Gradle이 의존성을 받는다. 두 번째부터는 캐시된다.
