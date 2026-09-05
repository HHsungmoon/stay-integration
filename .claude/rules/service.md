---
paths:
  - "app/src/main/java/**/service/**/*.java"
  - "app/src/main/java/**/*Service.java"
---

# Service 작성 규칙

- `@Service @RequiredArgsConstructor @Transactional(readOnly = true)`.
  **쓰기 메서드에만** `@Transactional`을 붙여 readOnly를 해제한다.
- `@Transactional` 경계는 **service 계층에만** 둔다. function은 자기 트랜잭션을 열지 않고 여기 참여한다.
- **repository를 직접 주입하지 않는다.** 데이터 접근은 `function`(`*Reader` / `*Store`)을 통한다.
  다른 서비스의 데이터가 필요해도 그 서비스를 주입하지 않는다 — function을 쓴다. 순환 참조가 생길 길이 없어진다.
- 변수명은 축약하지 않는다. `service`·`repo`·`roomTypes`가 아니라 `catalogSyncService`·`roomTypeMappingRepository`.
- **생성자 주입만** — 필드 주입 금지.
- 엔티티 ↔ DTO 매핑은 여기서 한다.

## 검색 오케스트레이션에서 특히 지킬 것

- **리액티브 체인 안에서 JPA를 호출하지 않는다.** 매핑은 체인 시작 전에 한 번에 읽어 Map으로 넘긴다.
  이 규칙은 패키지 의존성으로 막히지 않으므로 **여기서 사람이 지켜야 한다.**
- **`.block()`은 컨트롤러 경계에서 단 한 번.** 체인 중간의 block은 이벤트 루프를 막는다.
- 공급사 실패는 예외로 전파하지 않는다 — `SupplierResult` 값으로 받아 합친다.
  성공 개수로 `status`(`OK` | `PARTIAL` | `ALL_FAILED`)를 정하고, 실패한 공급사를 응답에 담는다.
- 검색은 셋으로 나눈다: `StaySearchService`(진입점 — lookup 읽기는 체인 **밖**) → `SupplierAvailabilityFetcher`(청크·동시성·데드라인.
  결과를 해석하지 않는다) → `SearchResultAssembler`(병합·ID 변환·D-10·D-11·상태 판정. 순수 함수). fetcher와 assembler는 `catalog`의
  `CatalogLookup` 값만 알고 JPA를 모른다.
- 데드라인은 **공급사 단위**로 `take(deadline)` — 도착한 청크는 살리고 안 온 청크 수만큼 `TIMEOUT`을 채운다(D-9). 청크 수 = 결과 수여야 `failedCalls`가 정직하다.
- `SKIPPED`(호출하지 않음)는 성공에도 실패에도 세지 않는다. 전부 SKIPPED면 `OK` + 빈 items.
- 관측성 지표는 **service의 병합 지점**에서 `common.SupplierCallMetrics`에 `SupplierResult`를 넘겨 기록한다(검색은 `.map` 안, 동기화는 결과 루프).
  assembler는 순수 함수로 남긴다 — 레지스트리를 주입하면 단위 테스트가 레지스트리를 알아야 한다. 태그 이름은 service가 모른다.
- `search`·`catalog`는 `supplier.adapter..`에 의존하지 않는다 — **포트만 안다.** 어댑터는 레지스트리로 주입된다.
