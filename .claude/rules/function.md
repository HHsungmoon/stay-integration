---
paths:
  - "app/src/main/java/**/function/**/*.java"
---

# function 계층 규칙

repository 접근을 모으는 계층. `controller → service → function → repository` 단방향에서 세 번째 자리다.

## 왜 있는가

- 서비스가 repository를 직접 잡으면 서비스끼리 서로의 데이터가 필요할 때 **상대 서비스를 주입**하게 되고, 그게 순환 참조의 시작이다.
  서비스가 function만 알면 그 길이 구조적으로 없다.
- 두 repository를 읽어 하나로 조립하거나, 포트 타입을 엔티티로 바꾸는 코드가 서비스에 있으면 서비스가 비대해진다. 그 일은 여기서 한다.

## 규칙

- **repository는 여기서만 호출한다.** service·controller에서 `*Repository`를 주입받으면 위반.
- **순수 위임을 두지 않는다.** `findById`를 그대로 넘기기만 하는 메서드는 계층이 아니라 소음이다.
  조립(여러 repository → 하나의 결과), 변환(포트 타입 → 엔티티), 쿼리 선택(어느 조회를 쓸지)이 있을 때만 메서드를 만든다.
- **트랜잭션을 열지 않는다.** `@Transactional`은 service에만. function은 호출한 service의 트랜잭션에 참여한다.
  그래야 읽어 온 엔티티가 service 안에서 managed 상태로 남아 도메인 메서드의 변경이 dirty checking으로 반영된다.
- `@Component`. `@Service`는 service 계층에만 쓴다 — 스테레오타입으로 계층이 읽혀야 한다.
- 이름: 읽기는 `*Reader`, 쓰기는 `*Store`. 서비스의 `*Synchronizer`·`*Loader`와 접미사가 겹치지 않게 한다.
- 조립 결과는 function 안에 중첩 record로 둔다(`CatalogMappingReader.ExistingMappings`). 그 record는 이 계층의 출력이지 HTTP DTO가 아니다.
- 변수명은 축약하지 않는다 — `propertyMappingRepository`, `roomTypeMappingRepository`.

## 이 저장소의 예

| function | 하는 일 |
|---|---|
| `CatalogMappingReader.existingBySupplier` | 두 repository를 읽어 코드 → 엔티티 Map 둘로 조립 |
| `CatalogMappingReader.activeRoomTypesWithProperty` | JOIN FETCH 쿼리 선택 |
| `CatalogMappingReader.countsOf` | 네 번의 count를 한 record로 |
| `CatalogLookupReader.load` | 엔티티 → 검색용 `CatalogLookup` 조립. search가 catalog **서비스**가 아니라 이 function을 주입한다 — feature 간 데이터 공유가 function으로 가는 첫 예 |
| `CatalogMappingStore.createProperty / createRoomType` | 포트 타입 → 엔티티 생성 + 저장. 갱신은 도메인 메서드가 하므로 여기 없다 |
