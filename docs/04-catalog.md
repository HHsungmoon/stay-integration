# 카탈로그 매핑 — 엔티티와 동기화 설계

작성일: 2026-09-05
상태: 구현 완료 (2026-09-05). 구현하며 달라진 판단은 §13, 동작 설명은 §14.

## 요약 — catalog는 무엇을 하는가

공급사 API는 지역으로 검색해 주지 않는다. "A-10023, A-10044의 재고를 알려줘"처럼 **숙소 코드 목록을 넘겨야** 하므로,
검색 전에 어떤 코드가 존재하는지 우리가 먼저 알고 있어야 한다. 그 목록의 출처가 공급사의 숙소 목록 API이고,
받은 것을 저장해 두는 것이 `catalog`다. 두 번째 역할은 **번역**이다 — 고객에게는 공급사 코드가 아니라 우리 식별자를
보여줘야 하고, 그 대응표가 여기 있다.

저장하는 것은 숙소·객실 타입 두 테이블의 **(공급사, 코드) → 내부 ID 대응과 이름 스냅샷**뿐이다.
요금·재고는 매 호출마다 바뀌어 저장하지 않는다 — 이 시스템이 DB에 두는 것은 이 매핑이 유일하다.

동기화는 **기동 시 1회**와 **관리 엔드포인트**로 돌며, 기존 매핑과 비교해 새 코드는 생성, 있는 코드는 갱신,
사라진 코드는 비활성화한다. **한 번 발급된 ID는 바뀌지 않는다** — 사라진 상품도 삭제하지 않고 `active=false`로 두어
재등장 시 같은 ID로 살린다. 공급사 하나가 실패해도 다른 공급사는 갱신된다.
검색은 시작 시 이 표를 한 번 읽어 메모리에 올리고, 그 뒤로는 DB를 보지 않는다.

---

## 0. 이 단계의 범위 — 포트를 먼저 정의한다

동기화는 공급사 숙소 목록 API를 호출한다. 그 호출은 어댑터가 하는데, 어댑터는 다음 단계다.
그래서 이 단계에서 **어댑터가 구현할 인터페이스(포트)와 결과 타입을 먼저 정의**하고, `catalog`는 포트에만 의존한다.
테스트는 포트를 직접 구현한 stub으로 돈다. 어댑터가 들어와도 `catalog`는 바뀌지 않는다 — 경계 2가 처음부터 지켜진다.

| 이 단계에서 만드는 것 | 패키지 |
|---|---|
| `SupplierId`, `CatalogProperty`, `CatalogRoomType` (표준 모델 — 카탈로그 부분) | `supplier.port` |
| `SupplierResult<T>` (sealed — Success / Failure / Skipped 골격) | `supplier.port` |
| `SupplierAdapter` 인터페이스, `SupplierRegistry` | `supplier.port` |
| `PropertyMapping`, `RoomTypeMapping` 엔티티 + repository | `catalog` |
| `CatalogSyncService`, `CatalogSyncRunner`(기동 시), `CatalogAdminController` | `catalog` |
| `CatalogLookup` (검색 경로가 쓸 in-memory 스냅샷 — 타입과 로더만. 로더는 06 단계에서 `function`으로 이동) | `catalog` |

재고·요금 조회(`fetchAvailability`)는 **어댑터 단계에서 인터페이스에 추가**한다. 지금 선언하려면 `Offer`·`AvailabilityQuery`를
빈 껍데기로 만들어야 하는데, 설명할 수 없는 코드는 두지 않는다. 메서드가 추가되면 테스트 stub도 그때 함께 고친다.

---

## 1. 무엇을 저장하고 무엇을 저장하지 않나

**저장**: 공급사가 쓰는 숙소·객실 타입 코드와 우리 내부 식별자의 대응. 그리고 동기화 시점의 이름·수용 인원 스냅샷.

**저장하지 않음**: 요금, 재고, 검색 이력, 원본 응답. 원본은 외부에 있고 매 호출마다 달라진다. 쌓아도 다음 검색 때 쓸 수 없다.

매핑이 편의가 아니라 **조회의 전제**인 이유 — 공급사는 지역으로 검색해 주지 않는다. 재고·요금 API는 **숙소 코드 목록을 받는다.**
어떤 코드를 물어볼지 우리가 먼저 알아야 하고, 그 목록의 출처가 숙소 목록 API다. 그 결과를 저장해 두는 것이 매핑이다.

---

## 2. 엔티티

### 2.1 두 테이블

| 엔티티 | 테이블 | 유니크 키 |
|---|---|---|
| `PropertyMapping` (숙소) | `property_mapping` | `(supplier, supplier_hotel_code)` |
| `RoomTypeMapping` (객실 타입) | `room_type_mapping` | `(supplier, supplier_hotel_code, supplier_room_type_code)` |

**객실 타입 유니크 키에 숙소 코드가 들어가는 이유** — 객실 타입 코드는 **숙소 안에서만 유일**하다.
Mock 데이터의 `STD-DBL`이 `A-10023`과 `A-10044` 양쪽에 있는 것이 이 케이스다. 키를 `(supplier, room_type_code)`로 잡으면 두 행이 충돌한다.

### 2.2 컬럼

**`PropertyMapping`**

| 컬럼 | 타입 | 의미 |
|---|---|---|
| `id` | bigint IDENTITY | **내부 숙소 식별자.** 응답에 노출되는 값 |
| `supplier` | varchar, not null | 공급사 식별자 (§3.1) |
| `supplier_hotel_code` | varchar, not null | 공급사의 숙소 코드 |
| `supplier_hotel_name` | varchar, not null | 동기화 시점 스냅샷. 응답에는 재고·요금 응답 값을 우선한다(D-11) |
| `active` | boolean, not null | 공급사 목록에 현재 있는지. false면 검색 대상에서 제외 |
| `created_at` / `updated_at` | timestamptz | |
| `last_synced_at` | timestamptz | 마지막으로 공급사 목록에서 확인된 시각 |

**`RoomTypeMapping`**

| 컬럼 | 타입 | 의미 |
|---|---|---|
| `id` | bigint IDENTITY | **내부 객실 타입 식별자** |
| `property_id` | FK → property_mapping, not null, LAZY | 소속 숙소 |
| `supplier` | varchar, not null | **property의 값을 복사** (아래) |
| `supplier_hotel_code` | varchar, not null | **property의 값을 복사** |
| `supplier_room_type_code` | varchar, not null | |
| `supplier_room_type_name` | varchar, not null | 스냅샷 |
| `max_occupancy` | int, not null | 스냅샷. 응답에는 재고·요금 응답 값을 우선(D-11) |
| `active` / `created_at` / `updated_at` / `last_synced_at` | | property와 동일 |

**`supplier`·`supplier_hotel_code`를 객실 타입에도 복사하는 이유** — 정규화하면 FK만 두고 유니크를 `(property_id, supplier_room_type_code)`로 걸 수 있다. 그래도 의미는 같다. 그런데도 복사하는 이유는 둘이다.

1. **검색 경로의 역방향 조회가 단일 테이블로 끝난다.** 어댑터 응답의 `(hotelCode, roomTypeCode)`를 내부 ID로 바꾸는 lookup을 만들 때 join이 필요 없다.
2. **유니크 제약이 스펙의 식별자 규칙을 그대로 표현한다.** 문서의 "(공급사, 숙소 코드, 객실 타입 코드) 세 값이 필요하다"가 DDL과 1:1로 대응한다.

복사 컬럼의 불일치 위험은 **생성자에서 property로부터 채우고 이후 변경 불가**로 닫는다. setter가 없으므로 어긋날 경로가 없다.

### 2.3 내부 식별자는 IDENTITY Long

응답의 "내부 숙소 식별자"는 PK 그대로다. 순번이 노출되지만 이 시스템의 범위에서 문제가 아니다.

**멱등성이 걸린 곳이 여기다.** 같은 `(supplier, code)`가 다시 와도 새 행을 만들지 않으면 ID는 유지된다. 유니크 제약이 그것을 DB 레벨에서 보장한다 — 애플리케이션 로직이 실수로 두 번 insert하면 두 번째가 실패한다.

### 2.4 도메인 메서드 (setter 없음)

```java
// PropertyMapping / RoomTypeMapping 공통
void refresh(String name, [int maxOccupancy,] Instant now)   // 스냅샷 갱신 + active = true + lastSyncedAt
void deactivate(Instant now)                                   // active = false + updatedAt
```

`refresh`가 `active = true`를 포함하는 이유 — 목록에서 사라졌다가 재등장한 상품이 **같은 ID로** 살아나야 한다.
새 ID를 발급하면 멱등성이 깨진다. 이것이 하드 삭제를 하지 않는 이유다.

시각은 서비스가 `Clock`에서 읽어 넘긴다. 테스트에서 고정할 수 있고, 엔티티가 시계를 모른다.

---

## 3. 포트 — `supplier.port`

### 3.1 `SupplierId`는 enum이 아니라 값 객체

```java
public record SupplierId(String value) { ... }     // "A", "B"
```

enum이면 타입 안전하고 `switch`가 빠뜨림을 잡아준다. 그래도 String을 택한 이유:
**"신규 공급사 추가 시 고칠 것 = 어댑터 1개 + 설정 + 레지스트리 등록"이라고 못 박았다.** enum이면 그 목록에 "enum 상수 추가"가 붙어 목록이 거짓이 된다. 값이 설정(yaml)에서 오는 런타임 값이기도 하다.

DB 컬럼은 `varchar`. `entity.md`의 "Enum 컬럼은 STRING" 규칙은 enum을 쓸 때의 규칙이고, 여기선 enum을 쓰지 않는 것이다.

### 3.2 표준 모델 (카탈로그 부분)

```java
public record CatalogProperty(String code, String name, List<CatalogRoomType> roomTypes) {}
public record CatalogRoomType(String code, String name, int maxOccupancy) {}
```

공급사 A의 `hotelCode`/`roomTypeCode`도, B의 `propertyId`/`roomId`도 여기로 번역된다. `catalog`는 원래 이름을 모른다.

### 3.3 `SupplierResult<T>` — 골격

```java
public sealed interface SupplierResult<T> permits Success, Failure, Skipped {
    SupplierId supplier();
    Duration elapsed();

    record Success<T>(SupplierId supplier, T value, Duration elapsed) implements SupplierResult<T> {}
    record Failure<T>(SupplierId supplier, FailureKind kind, String detail, Duration elapsed) implements SupplierResult<T> {}
    record Skipped<T>(SupplierId supplier, String reason, Duration elapsed) implements SupplierResult<T> {}
}
```

`Skipped`를 **지금** 넣는다. 서킷 브레이커 단계에서 추가하면 그때 처리하지 않은 switch가 전부 컴파일 에러로 드러나는 효과가 있지만, 이 단계에서는 케이스가 셋이라는 계약을 먼저 세우는 편이 낫다 — 카탈로그 동기화도 "서킷이 열려 있으면 건너뛴다"를 지금부터 처리해야 한다.

제네릭인 이유 — 카탈로그 조회는 `SupplierResult<List<CatalogProperty>>`, 재고·요금은 `SupplierResult<List<Offer>>`. 타입 하나로 두 API의 결과를 다루므로 관측성 지표를 한 곳에서 낼 수 있다.

`FailureKind`의 세부는 어댑터 단계에서 확정한다. 여기서는 enum 골격만. → 05에서 재시도 여부 3분류가 아니라 **원인 8값 + `retryable()`**로 확정됐다.

### 3.4 `SupplierAdapter`

```java
public interface SupplierAdapter {
    SupplierId id();
    Mono<SupplierResult<List<CatalogProperty>>> fetchCatalog();
    // fetchAvailability(...)는 어댑터 단계에서 추가한다(§0)
}
```

`Mono`를 반환하는 이유 — 검색 경로에서 병렬 호출·타임아웃 제어에 필요하다. 카탈로그 동기화는 블로킹 컨텍스트지만, 포트가 두 스타일을 섞는 것보다 동기화 쪽이 한 번 `block()`하는 편이 낫다(§5.2).

### 3.5 `SupplierRegistry`

Spring이 어댑터 빈을 모아 주면(`ObjectProvider<SupplierAdapter>` — 어댑터가 0개여도 컨텍스트가 떠야 한다) `id()`로 `Map`을 만든다. **id가 중복이면 기동 실패**(fail-fast) —
같은 공급사 어댑터가 둘 등록되는 설정 실수를 조용히 넘기지 않는다. 명시적 `List<SupplierAdapter>` 생성자도 있다 — 실제 어댑터가 빈으로 있는 컨텍스트에서
테스트가 stub만 담은 레지스트리로 갈아 끼우는 경로다(05 §14).

`catalog`와 `search`는 이 레지스트리만 안다. 어댑터 구현 클래스를 import하는 순간 ArchUnit이 빌드를 깨뜨린다(경계 2).

---

## 4. 동기화 알고리즘

공급사 하나에 대해, 트랜잭션 하나 안에서:

```
입력: supplier S, 공급사가 준 List<CatalogProperty> incoming, 시각 now

1. S의 기존 매핑을 전부 읽는다 — property와 roomType 각각 Map<code, entity>
   (한 공급사의 카탈로그 전체는 수천 개여도 메모리에 들어간다)

2. incoming의 각 property P:
     기존에 있으면  → refresh(P.name, now)           [updated 또는 reactivated]
     없으면        → 새로 생성                        [created]
     P의 각 roomType R:
       기존에 있으면 → refresh(R.name, R.maxOccupancy, now)
       없으면       → 생성 (property 참조 + supplier/hotelCode 복사)

3. 기존에 있는데 incoming에 없는 property / roomType → deactivate(now)   [deactivated]
   (roomType은 자기 키로 각각 판정한다 — property가 통째로 사라지면 그 아래 roomType 키도 전부 없으므로 함께 비활성화된다)

4. 결과 집계 → SyncOutcome(created, updated, reactivated, deactivated)
   (별도 saveAll 없음 — 생성은 store가 저장하고, 갱신·비활성화는 트랜잭션 안의 dirty checking이 반영한다)
```

**공급사가 같은 코드를 두 번 주면** 두 번째는 건너뛰고 `warn`을 남긴다(구현 시 추가). 그대로 두면 두 번째 insert가 유니크 제약에 걸려 **트랜잭션 전체**가 실패하고,
그 공급사의 정상 상품까지 반영되지 않는다.

### 4.1 멱등성의 정의 — 테스트가 이것을 증명한다

같은 `incoming`으로 N번 실행하면:

| 불변 | 검증 |
|---|---|
| 내부 ID가 바뀌지 않는다 | 1회차 ID 집합 == N회차 ID 집합 |
| 행 수가 늘지 않는다 | `count()` 동일 |
| `active` 상태가 같다 | 전부 true |
| 2회차 이후 `created = 0` | outcome 확인 |

그리고 **사라졌다 재등장하는 경우**:

| 회차 | incoming | 기대 |
|---|---|---|
| 1 | `A-10023`, `A-10044` | 둘 다 생성, active |
| 2 | `A-10023`만 | `A-10044` **비활성**, ID 유지, 행 유지 |
| 3 | `A-10023`, `A-10044` | `A-10044` **같은 ID로** 재활성 (`reactivated = 1`) |

3회차에서 새 ID가 나오면 멱등성 위반이다. 하드 삭제를 했다면 여기서 깨진다.

### 4.2 DB upsert(ON CONFLICT)를 쓰지 않는 이유

PostgreSQL의 `INSERT ... ON CONFLICT DO UPDATE`가 정확히 upsert다. 그런데도 애플리케이션 레벨(읽기 → 비교 → 저장)을 택한 이유:

- JPA에서 native upsert는 `@Query(nativeQuery)`로 가야 하고, 생성된 ID를 엔티티에 되돌리는 처리가 번거롭다.
- 카탈로그는 작고(수천 건) 동기화는 요청 경로 밖이라 성능이 관심사가 아니다.
- 읽기 → 비교 → 저장이 **"무엇이 생기고 무엇이 사라졌는지"를 집계하기에 자연스럽다.** upsert는 그 정보를 주지 않는다.

**동시에 두 번 돌면?** 유니크 제약 때문에 둘째 트랜잭션의 insert가 실패해 롤백된다 — DB가 멱등성을 지킨다. 다만 실패로 끝나는 것보다 아예 안 시작하는 편이 나으므로, 서비스는 진행 중 플래그(`AtomicBoolean`)로 **동시 실행을 거절**한다(§6, 409). 단일 인스턴스 가정이며, 다중 인스턴스는 남는 문제(§11).

### 4.3 이름·수용 인원이 바뀌면

스냅샷을 갱신하고 `info` 로그를 남긴다. 응답에서는 어차피 재고·요금 응답의 값을 우선하므로(D-11) 스냅샷은 참고용이다.

---

## 5. 실패 처리 — D-8

### 5.1 공급사 하나의 실패가 다른 공급사를 막지 않는다

```
adapters = registry.all()
results  = Flux.fromIterable(adapters)
             .flatMap(adapter -> adapter.fetchCatalog()
                 .timeout(syncDeadline, → Failure(TIMEOUT))   // 공급사별 데드라인(3s). 병합 스트림 전체에 걸면 하나가 늦을 때 먼저 온 결과까지 잃는다
                 .onErrorResume(e -> Failure(UNEXPECTED)))    // 어댑터 계약 위반 안전망
             .collectList()
             .block()                                          // ← 한 번. 데드라인은 위에서 이미 걸려 있어 유한하다

for result in results:
    switch (result) {
        case Success s -> apply(s.supplier(), s.value(), now)   // @Transactional — 공급사별로 독립
        case Failure f -> report.failed(f)                       // 기존 매핑 유지. warn 로그
        case Skipped k -> report.skipped(k)
    }
```

A가 Failure면 A의 기존 매핑은 그대로고 B는 정상 갱신된다. 트랜잭션이 공급사별로 나뉘어 있어 B 저장 중 예외가 나도 A에 영향이 없다.

### 5.2 `block()`의 위치 — 스레드 모델 규칙과의 관계

CLAUDE.md는 "`.block()`은 컨트롤러 경계에서 단 한 번"이라고 한다. 그 규칙의 의도는 (1) 이벤트 루프 스레드에서 블로킹하지 않는다 (2) 체인 중간에서 블로킹하지 않는다, 두 가지다.

동기화는 **검색 경로가 아니다.** 진입점은 관리 엔드포인트(톰캣 워커) 또는 기동 러너(main 스레드)이고, 둘 다 블로킹해도 되는 스레드다. 그리고 위 구조에서 `block()`은 병합된 스트림 끝에서 **한 번**이며, JPA 저장은 `block()` **이후** 순차로 한다 — 체인 안에 JPA가 없다. 규칙의 의도를 그대로 지킨다.

**CLAUDE.md 문구를 "컨트롤러 경계 또는 동기화 진입점에서 단 한 번"으로 보정할 필요가 있다**(§12).

### 5.3 기동 시 1회

`ApplicationReadyEvent` 리스너에서 `sync()`를 호출한다. `ApplicationRunner`가 아닌 이유 — Ready 이벤트는 앱이 완전히 뜬 뒤라, 여기서 무슨 일이 나도 기동 자체는 이미 성공한 상태다. 그래도 리스너 안에서 예외가 새면 로그가 지저분해지므로 전체를 try-catch로 감싼다. `sync()` 자체는 실패를 값으로 다루니 예외가 날 일은 없어야 하지만, 보수적으로.

설정 `catalog.sync-on-startup`(기본 `true`). **테스트에서 끈다** — 컨텍스트가 뜰 때마다 Mock을 호출하면 테스트 간 간섭이 생긴다.

기동 시 Mock이 안 떠 있으면 → 연결 실패(500ms) → 전 공급사 Failure → 매핑 없음 → 검색 결과가 비지만 앱은 정상. 로그에 `warn`으로 남는다. **이건 장애가 아니라 "동기화 전 상태"다.**

---

## 6. 관리 엔드포인트

```
POST /admin/catalog/sync      → 200 SyncReport | 409 (이미 진행 중)
GET  /admin/catalog           → 200 공급사별 active/inactive 개수 (디버깅용)
```

```json
{
  "startedAt": "2026-10-01T09:00:00Z",
  "durationMs": 412,
  "suppliers": [
    { "supplier": "a", "status": "SUCCESS", "outcome": { "created": 4, "updated": 0, "reactivated": 0, "deactivated": 0 }, "failure": null },
    { "supplier": "b", "status": "FAILED",  "outcome": null, "failure": { "kind": "TIMEOUT", "detail": "catalog fetch exceeded PT3S" } },
    { "supplier": "c", "status": "SKIPPED", "outcome": null, "failure": { "kind": "SKIPPED", "detail": "circuit open" } }
  ]
}
```

(구현 형태 — 집계는 `outcome`, 실패는 `failure` 객체로 묶인다. 저장 단계에서 실패하면 `failure.kind = "PERSISTENCE"`.)

인증은 없다(비범위). 요구사항의 비범위 "관리자 기능"은 관리 UI·권한을 뜻하고, 재동기화 트리거는 D-8에서 확정한 필수 동작이다.

컨트롤러는 얇다 — 서비스 호출과 반환만. 409는 서비스가 던지는 `SyncAlreadyRunningException`을 `GlobalExceptionHandler`가 매핑한다. 이 단계에서 `GlobalExceptionHandler`의 최소 골격이 함께 생긴다.

---

## 7. 검색 경로가 쓰는 조회 — `CatalogLookup`

검색 시작 시 **한 번에 읽어** in-memory 스냅샷을 만든다. 리액티브 체인 안에서 JPA를 부르지 않기 위한 장치다(스레드 모델 1).

```java
public record CatalogLookup(
    Map<SupplierId, List<String>> activeHotelCodesBySupplier,          // 공급사에 물어볼 코드 목록 → 청크 분할 입력
    Map<RoomTypeKey, RoomTypeRef> byKey                                  // (supplier, hotelCode, roomTypeCode) → 내부 ID·이름
) {}
```

로더: `RoomTypeMappingRepository.findAllActiveWithProperty()` — `JOIN FETCH property`로 **한 쿼리**. N+1이 나면 그 자체가 스레드 모델 위반의 씨앗이다(`repository.md`).

비활성 매핑은 lookup에 없다 → 공급사에 물어보지 않는다. 그런데 공급사가 응답에 비활성(또는 미매핑) 상품을 섞어 보내면 → lookup miss → 해당 항목만 제외 + 카운터(D-10). 그 처리는 검색 단계.

---

## 8. 검토했다 버린 대안

| 대안 | 버린 이유 |
|---|---|
| 내부 ID를 UUID로 | 다중 인스턴스·병합에 유리하지만 지금 범위에선 오버스펙. 순번 노출은 이 시스템에서 문제가 아니다 |
| `SupplierId`를 enum으로 | 타입 안전은 얻지만 "신규 공급사 추가 시 고칠 것" 목록이 거짓이 된다(§3.1) |
| 유니크를 `(property_id, room_type_code)`로만 | 의미는 같으나 검색 lookup에 join이 필요하고, DDL이 스펙의 식별자 규칙을 직접 말하지 않는다(§2.2) |
| DB `ON CONFLICT` upsert | 집계 정보를 잃고 JPA와 어색하다. 성능이 관심사가 아니다(§4.2) |
| Spring Data JPA Auditing(`@CreatedDate`) | `Clock` 주입이 테스트에 더 낫고, 엔티티가 시계를 몰라야 한다. 감사 컬럼이 3개뿐이라 수동이 부담이 아니다 |
| 하드 삭제 + 재생성 | 재등장 시 새 ID → 멱등성 위반. 이것이 `active` 플래그의 존재 이유 |
| 기동 시 동기화를 별도 스레드로 | 기동이 최대 데드라인(3s)만큼 늦어지는 것을 감수한다. Mock이 없으면 500ms에 끝난다 |

---

## 9. 테스트 계획

| 층 | 대상 | 검증 |
|---|---|---|
| `@DataJpaTest` + Testcontainers | 엔티티·제약 | **유니크 제약이 DB에 실제로 생겼는지** — 같은 키 두 번 insert → `DataIntegrityViolationException`. `STD-DBL`을 두 숙소에 넣어도 충돌 없음 |
| 서비스 (stub 어댑터) | `CatalogSyncService` | §4.1 멱등성 표 전부. 사라짐 → 재등장 시 같은 ID. 이름 변경 시 스냅샷 갱신 |
| 서비스 (stub 어댑터) | D-8 | A = Failure, B = Success → A 기존 유지, B 갱신, 리포트에 A FAILED |
| 서비스 | 동시 실행 | 두 스레드가 동시에 `sync()` → 하나는 409 |
| `@WebMvcTest` | 관리 컨트롤러 | 200 리포트 형태, 409 매핑 |
| `@DataJpaTest` | `CatalogLookup` 로더(`CatalogLookupReader`) | 비활성 제외, 한 쿼리(`JOIN FETCH`) — Hibernate 통계로 쿼리 수 1 확인 |

Mock 서버를 실제로 붙이는 통합 테스트는 **어댑터 단계**에서. 여기서는 포트 stub으로 `catalog`의 책임만 검증한다.

---

## 10. 파일 구조

```
app/src/main/java/com/demo/stayintegration/
├── supplier/port/
│   ├── SupplierId.java
│   ├── CatalogProperty.java · CatalogRoomType.java
│   ├── SupplierResult.java              sealed: Success · Failure · Skipped
│   ├── FailureKind.java                 골격 enum
│   ├── SupplierAdapter.java             interface
│   └── SupplierRegistry.java
├── catalog/
│   ├── CatalogProperties.java           설정 — 레이어 것이 아니라 루트
│   ├── controller/  CatalogAdminController              §6
│   ├── service/     CatalogSyncService                  §4 · §5 — 오케스트레이션. 트랜잭션 밖
│   │                CatalogMappingSynchronizer           @Transactional synchronize() — diff 적용. 공급사별 독립. self-invocation 회피로 분리
│   │                CatalogSyncRunner                    ApplicationReadyEvent
│   │                SyncAlreadyRunningException
│   ├── function/    CatalogMappingReader                 repository 읽기 조립 — existingBySupplier · activeRoomTypesWithProperty · countsOf
│   │                CatalogLookupReader                  §7 — 검색용 스냅샷 조립. 06 단계에서 service → function으로 이동(search가 서비스가 아니라 function을 쓰도록)
│   │                CatalogMappingStore                  포트 타입 → 엔티티 생성·저장
│   ├── repository/  PropertyMappingRepository · RoomTypeMappingRepository   function에서만 호출
│   ├── entity/      PropertyMapping · RoomTypeMapping
│   └── dto/         CatalogLookup                        읽기 모델 — HTTP가 아니라 dto 루트
│       └── response/ SyncReport · SyncOutcome · CatalogSummary
└── common/
    ├── ClockConfig.java                 Clock 빈
    └── GlobalExceptionHandler.java      최소 골격 (409) — 06에서 400 셋(누락·형식·검색 규칙)이 추가된다
```

이 트리는 카탈로그 단계 시점이다. `common`에는 08에서 `ErrorResponse`·`SupplierCallMetrics`가 더 생겼다.

---

## 11. 남는 문제 (설계로 남긴다)

- **`ddl-auto=update`는 기존 테이블의 제약을 바꾸지 않는다.** 처음 생성 시엔 유니크 제약이 만들어지지만, 나중에 제약을 바꾸면 반영되지 않는다. 테스트(Testcontainers)와 로컬(compose, 볼륨 없음)은 매번 새 DB라 문제가 없다. 실운영이라면 Flyway + `validate`가 맞다는 판단의 근거가 하나 더 늘었다.
- **다중 인스턴스에서의 동시 동기화** — `AtomicBoolean`은 인스턴스 안에서만 유효하다. DB advisory lock이나 리더 선출이 필요하지만 이 범위 밖.
- **주기 스케줄** — D-8대로 설계로만. `@Scheduled` 한 줄이면 되지만, 스케줄러 인프라를 넣으면 테스트 컨텍스트가 무거워진다.
- **인덱스** — 유니크 제약이 인덱스 역할을 한다. 수천 개로 늘면 `(supplier, active)` 인덱스를 검토한다.
- **비활성 매핑의 정리** — 영구히 남는다. 그것이 의도다(재등장 대비). 정리 정책은 운영 판단.

---

## 12. 이 설계로 갱신이 필요한 문서

| 문서 | 무엇 |
|---|---|
| `CLAUDE.md` 스레드 모델 2 | "`.block()`은 컨트롤러 경계에서 단 한 번" → "컨트롤러 경계 **또는 동기화 진입점**에서 단 한 번" (§5.2) |
| `CLAUDE.md` Architecture | `SupplierId`가 String 값 객체임을 한 줄 (§3.1) |
| `.claude/rules/entity.md` | 복사 컬럼(`supplier`, `supplier_hotel_code`)이 생성자에서만 채워지고 불변이라는 것 |
| `.claude/rules/adapter.md` | `SupplierResult<T>`가 제네릭이고 `Skipped`가 처음부터 있다는 것 |

구현에 들어가기 전에 위 네 곳을 먼저 맞춘다(규칙 17).

## 13. 구현 중 추가된 판단 (2026-09-05)

- **`function` 계층 도입.** 서비스가 repository를 직접 잡지 않고 `CatalogMappingReader` / `CatalogMappingStore`를 통한다.
  서비스끼리 서로를 주입할 이유가 없어져 순환 참조가 구조적으로 막히고, 두 repository를 Map 둘로 조립하는 코드가
  서비스에서 빠져 서비스가 비즈니스 단계만 읽힌다. 순수 위임은 두지 않는다. 트랜잭션은 여전히 service에만.
- **`CatalogMappingWriter` → `CatalogMappingSynchronizer`.** function의 `*Store`와 접미사가 헷갈리고, 실제로 하는 일이 diff 적용이라 이 이름이 정직하다.
- **feature 안 레이어 서브패키지.** `controller / service / function / repository / entity / dto{,/response}`. 읽기 모델 `CatalogLookup`은 `dto` 루트.
- **builder를 쓰지 않는 이유.** 엔티티 필드가 전부 필수다. builder는 필드를 빠뜨려도 컴파일되고 런타임에 null이 된다.
  생성자는 빠뜨리면 컴파일 에러다. 선택 필드가 생기면 그때 `@Builder` + `@Builder.Default`.

---

## 14. 동작 설명 — 구현된 것을 따라가며

### 14.1 동기화 후 테이블 (Mock 데이터 기준)

**`property_mapping`**

| id | supplier | supplier_hotel_code | supplier_hotel_name | active |
|---|---|---|---|---|
| 1 | a | A-10023 | Riverside Hotel Seoul | true |
| 2 | a | A-10044 | Namsan Garden Stay | true |
| 3 | b | B77120 | Riverside Hotel Seoul | true |

`supplier` 값은 어댑터의 `SupplierId`(yaml 키 `a`·`b`)다. stub 테스트는 대문자 `A`·`B`를 쓰지만 운영 값은 소문자다.

**`room_type_mapping`**

| id | property_id | supplier | supplier_hotel_code | supplier_room_type_code | name | max |
|---|---|---|---|---|---|---|
| 1 | 1 | a | A-10023 | DLX-TWN | Deluxe Twin | 2 |
| 2 | 1 | a | A-10023 | STD-DBL | Standard Double | 2 |
| 3 | 1 | a | A-10023 | FAM-STE | Family Suite | 4 |
| 4 | 2 | a | A-10044 | **STD-DBL** | Standard Double | 2 |
| 5 | 3 | b | B77120 | R-401 | Deluxe Twin Room | 2 |
| 6 | 3 | b | B77120 | R-402 | Family Room | 4 |

`id`가 응답에 나가는 내부 식별자다. 2행과 4행은 같은 `STD-DBL`이지만 다른 숙소라 각자 ID를 갖는다 —
유니크 키에 숙소 코드가 들어가는 이유가 이 두 행이다.

### 14.2 동기화 한 번의 흐름

```
1. 공급사 A·B에 "숙소 목록 줘" — 동시에                         CatalogSyncService.fetchAll (block 1회)
2. 공급사 결과를 하나씩:
     성공 → 기존 매핑과 비교                                     CatalogMappingSynchronizer.synchronize (트랜잭션)
              새 코드        → 행 생성               created
              있는 코드      → 이름·수용 인원 갱신     updated
              사라졌던 코드   → 같은 행 다시 활성      reactivated
              이번에 안 온 코드 → active = false        deactivated
     실패·건너뜀 → 그 공급사는 건드리지 않음. 기존 매핑 그대로
3. 리포트 반환 — 공급사별 SUCCESS / FAILED / SKIPPED와 숫자     SyncReport
```

남산(`A-10044`)이 목록에서 빠지면 삭제하지 않고 `active=false`만 되고, 다시 나타나면 `id=2`가 그대로 살아난다.
삭제했다면 새 ID가 나왔을 것이다. A가 죽어도 B는 정상 갱신된다 — 트랜잭션이 공급사별로 따로다.

### 14.3 검색이 이 표를 쓰는 방식 (다음 단계)

검색이 시작되면 `CatalogLookupReader`(function)가 활성 매핑을 **한 번에** 읽어 메모리에 올린다.

```
공급사에 물어볼 코드:    A → [A-10023, A-10044],   B → [B77120]
응답을 우리 ID로 번역:   (A, A-10023, DLX-TWN) → 숙소 1 / 객실 타입 1 "Deluxe Twin"
```

그 뒤 검색은 DB를 다시 보지 않는다. 공급사 응답에 표에 없는 코드가 섞여 오면 그 항목만 빼고 카운터를 올린다(D-10).

### 14.4 클래스별 역할 — 요청이 흐르는 순서

```
CatalogAdminController          POST /admin/catalog/sync 를 받아 서비스 호출, 리포트 반환
  └ CatalogSyncService          공급사 병렬 호출 → 결과별 분기 → 리포트 조립. 동시 실행 거절(409)
      ├ SupplierRegistry        어댑터 목록 (supplier.port)
      └ CatalogMappingSynchronizer     공급사 하나의 diff 적용. 여기가 트랜잭션
          ├ CatalogMappingReader       기존 매핑을 읽어 코드 → 엔티티 Map으로 조립
          └ CatalogMappingStore        포트 타입 → 엔티티 생성·저장
CatalogSyncRunner               기동 시 위를 한 번 호출 (ApplicationReadyEvent)
CatalogLookupReader             검색용 스냅샷 조립(function) — CatalogMappingReader의 JOIN FETCH 조회 사용
```

### 14.5 테스트가 증명한 것 (이 단계 19개 → 현재 22개: `MappingConstraintTest` 4 · `CatalogSyncServiceTest` 10 · `CatalogAdminControllerTest` 3 · `CatalogLookupReaderTest` 2 · 05에서 추가된 `CatalogSyncWireTest` 3)

| 무엇 | 어떻게 |
|---|---|
| 유니크 제약이 **DB에 실제로** 생겼다 | `STD-DBL` 두 숙소는 통과, 같은 숙소 두 번은 `DataIntegrityViolationException` |
| 3번 돌려도 ID·행 수가 변하지 않는다 | 1회차 ID 집합 == 3회차, `created = 0` |
| 사라진 뒤 재등장하면 **같은 ID** | 2회차 비활성 → 3회차 `reactivated = 2`, ID 동일 |
| A 실패 시 A 유지, B 갱신 | stub A = Failure, B = 신규 숙소 추가 |
| 데드라인 초과해도 다른 공급사 결과는 살아 있다 | A 1.5s 지연(데드라인 1s) → A FAILED, B SUCCESS |
| lookup 조회가 **쿼리 1번** | Hibernate 통계 `getPrepareStatementCount() == 1` |
| 동시 실행 거절 | 두 스레드 → 하나는 `SyncAlreadyRunningException` → 409 |

---

## 미구현 항목

| 항목 | 이유 |
|---|---|
| 주기 동기화 스케줄 | D-8이 "기동 1회 + 관리 엔드포인트"로 확정했다. `@Scheduled` 한 줄이지만 운영 주기는 데이터 변경 빈도를 봐야 정하고, 스케줄러 인프라가 들어오면 테스트 컨텍스트가 무거워진다. 관리 엔드포인트가 그 자리를 대신한다 |
| 다중 인스턴스 동시 동기화 방지 | `AtomicBoolean`은 인스턴스 안에서만 유효하다. DB advisory lock·리더 선출은 단일 인스턴스 가정(README) 밖이다. 두 인스턴스가 동시에 돌면 유니크 제약이 둘째를 실패시켜 데이터는 지켜진다 |
| Flyway | 테이블 2개 규모에서 마이그레이션 이력이 산출물이 아니다. 유니크 제약은 엔티티 애노테이션이 유일한 선언 지점이고 실제 DB에 생기는지 테스트가 본다. `ddl-auto=update`가 기존 제약을 바꾸지 않는 한계는 §11에 |
| `(supplier, active)` 인덱스 | 유니크 제약이 인덱스 역할을 한다. 수천 개에서도 검색 스냅샷은 쿼리 1개라 필요가 드러나지 않았다 |
| 비활성 매핑 정리 | 영구히 남는 것이 의도다(재등장 시 같은 ID). 정리 정책은 운영 판단 |
| DB `ON CONFLICT` upsert | 집계 정보(created/updated/reactivated/deactivated)를 잃고 JPA와 어색하다. 성능이 관심사가 아니다(§4.2) |
