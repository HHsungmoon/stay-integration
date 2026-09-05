---
paths:
  - "app/src/main/java/**/service/**/*.java"
  - "app/src/main/java/**/*Service.java"
---

# Service 작성 규칙

- `@Service @RequiredArgsConstructor @Transactional(readOnly = true)`.
  **쓰기 메서드에만** `@Transactional`을 붙여 readOnly를 해제한다.
- `@Transactional` 경계는 **service 계층에만** 둔다.
- **생성자 주입만** — 필드 주입 금지.
- 엔티티 ↔ DTO 매핑은 여기서 한다.

## 검색 오케스트레이션에서 특히 지킬 것

- **리액티브 체인 안에서 JPA를 호출하지 않는다.** 매핑은 체인 시작 전에 한 번에 읽어 Map으로 넘긴다.
  이 규칙은 패키지 의존성으로 막히지 않으므로 **여기서 사람이 지켜야 한다.**
- **`.block()`은 컨트롤러 경계에서 단 한 번.** 체인 중간의 block은 이벤트 루프를 막는다.
- 공급사 실패는 예외로 전파하지 않는다 — `SupplierResult` 값으로 받아 합친다.
  성공 개수로 `status`(`OK` | `PARTIAL` | `ALL_FAILED`)를 정하고, 실패한 공급사를 응답에 담는다.
- 결과를 병합하는 **그 지점 한 곳에서** 관측성 지표를 기록한다. 계측 코드를 여러 곳에 흩지 않는다.
- `search`·`catalog`는 `supplier.adapter..`에 의존하지 않는다 — **포트만 안다.** 어댑터는 레지스트리로 주입된다.
