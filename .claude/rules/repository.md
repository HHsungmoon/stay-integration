---
paths:
  - "app/src/main/java/**/repository/**/*.java"
  - "app/src/main/java/**/*Repository.java"
---

# Repository 작성 규칙

- `interface XRepository extends JpaRepository<E, Id>`.
- **repository만 JPA 영속성 API에 접근한다.** service·controller는 영속성 API를 직접 쓰지 않는다.
- 단건 조회는 `Optional<T>`.
- 동기화는 **upsert로 멱등**하게. 여러 번 돌려도 내부 식별자가 바뀌지 않아야 하고, 이것을 테스트로 증명한다.

## 검색 경로의 조회는 한 번에 다 읽는다

리액티브 체인 안에서는 JPA를 호출할 수 없다(CLAUDE.md 스레드 모델).
매핑은 검색 시작 시점에 **한 번에 읽어 in-memory Map으로 만든 뒤** 체인에 넘긴다.

따라서 검색 경로용 조회 메서드는 N+1을 만들지 않는 형태여야 한다.
숙소별로 한 번씩 부르는 메서드를 만들면 그 자체가 스레드 모델 위반의 원인이 된다.
