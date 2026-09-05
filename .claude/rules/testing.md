---
paths:
  - "app/src/test/**/*.java"
  - "mock-supplier/src/test/**/*.java"
---

# 테스트 작성 규칙

`./gradlew build` = 컴파일 + 테스트. **완료 판정 게이트다**(규칙 16).
견고성은 "구현했다"가 아니라 **테스트가 근거**다.

## DB는 Testcontainers 실제 PostgreSQL (Docker 필요)

인메모리 DB는 방언 차이로 "테스트는 통과하는데 운영에서 깨지는" 상황을 만든다.
**매핑의 upsert·유니크 제약이 이 시스템의 불변 조건**이라 특히 안 된다.

**Testcontainers 2.x에서 좌표와 패키지가 바뀌었다** (2.0.5 기준 — 추측 금지)

| | 이전 | 현재 |
|---|---|---|
| 좌표 | `org.testcontainers:postgresql` | `org.testcontainers:testcontainers-postgresql` |
| 클래스 | `org.testcontainers.containers.PostgreSQLContainer`(deprecated) | `org.testcontainers.postgresql.PostgreSQLContainer` |
| 제네릭 | `PostgreSQLContainer<?>` | 없음 |

- `spring-boot-docker-compose`는 `developmentOnly`라 **테스트 클래스패스에 없다.**
  테스트의 DataSource는 `@ServiceConnection`을 단 컨테이너 빈이 공급한다.
- 이미지 태그는 고정한다(`postgres:18-alpine`, `compose.yaml`과 동일).

## Boot 4.1 슬라이스 애노테이션 패키지 (이동됨 — 추측 금지)

| 애노테이션 | 패키지 |
|---|---|
| `@WebMvcTest`, `@AutoConfigureMockMvc` | `org.springframework.boot.webmvc.test.autoconfigure.*` |
| `@DataJpaTest` | `org.springframework.boot.data.jpa.test.autoconfigure.*` |
| `@AutoConfigureTestDatabase` | `org.springframework.boot.jdbc.test.autoconfigure.*` |
| `TestEntityManager` | `org.springframework.boot.jpa.test.autoconfigure.*` |

마지막 항목이 함정이다 — `@DataJpaTest`와 **모듈·패키지 루트가 다르다**(`.data.jpa.test.`가 아니다).

슬라이스 선택: 영속 계층 → `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` +
`@Import(TestcontainersConfiguration.class)`. 웹 계층 → `@WebMvcTest(XxxController.class)`.

## 무엇을 어떤 층으로 검증하나

- **단위 테스트**(순수 함수라 가능하다): 총액 계산, 연박 재고 최솟값 판정, 누락 날짜 0 처리,
  본문 코드로만 알리는 실패의 판정.
- **통합 테스트 — 이 저장소에서 가장 중요한 테스트다.** Mock 모드(정상·장애·무응답)를 바꿔가며
  (1) 정상 병합 (2) 한쪽 무응답 → 나머지로 응답 + 실패 표시
  (3) 본문 코드 실패를 실패로 인식 (4) 전부 실패.
- **멱등성**: 카탈로그 동기화를 두 번 돌려 내부 식별자가 바뀌지 않는 것을 증명한다.
- ArchUnit `ArchitectureTest`가 아키텍처 경계 5개를 강제한다(**1.5.0+** — Java 25 바이트코드 파싱에 필요).

테스트 메서드명은 영어, 주석은 한국어.
