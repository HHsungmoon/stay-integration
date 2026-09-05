---
paths:
  - "app/src/main/java/**/entity/**/*.java"
  - "app/src/main/java/**/catalog/**/*.java"
---

# 엔티티 작성 규칙

저장 대상은 **공급사 코드 ↔ 내부 식별자 매핑뿐**이다. 요금·재고는 원본이 외부에 있으므로 쌓지 않는다.

- Lombok: **`@Getter` + `@NoArgsConstructor(access = PROTECTED)` 만.**
  금지: `@Data` · 전면 `@Setter` · 기본 `@EqualsAndHashCode`(양방향 무한재귀·lazy 트리거·가변 hashCode 버그원).
- PK: `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)`.
- 연관관계: `@ManyToOne(fetch = FetchType.LAZY)` — **EAGER 금지.**
- Enum 컬럼: `@Enumerated(EnumType.STRING)`.
- **상태 변경은 setter 금지 → 도메인 메서드**로 표현.
- `@Builder`를 쓰면 초기화 컬렉션·기본값 필드에 **`@Builder.Default` 필수**(없으면 null).
- 시간 타입: 기록된 순간 = `Instant` + `timestamptz`.
- `@Entity`는 controller 경계를 넘지 않는다 — 요청·응답은 DTO(매핑은 service).

## 유니크 제약을 반드시 애노테이션에 선언한다

Flyway를 쓰지 않고 `ddl-auto=update`로 가므로, **스키마의 유일한 선언 지점이 엔티티다.**
Hibernate는 이 선언을 보고 제약을 만든다. **애노테이션에 없으면 DB에도 제약이 없다.**

```java
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"supplier", "supplier_hotel_code"}))
```

| 매핑 | 유니크 키 |
|---|---|
| 숙소 | `(supplier, supplier_hotel_code)` |
| 객실 타입 | `(supplier, supplier_hotel_code, supplier_room_type_code)` |

객실 타입 코드는 **숙소 안에서만 유일**하므로 숙소 코드가 키에 반드시 포함된다.
이것이 "같은 공급사 상품은 항상 같은 내부 식별자"라는 불변 조건의 **실제 강제 지점**이다.

## 하드 삭제하지 않는다

공급사 목록에서 사라진 상품은 `active` 플래그로 비활성화한다.
삭제 후 재등장하면 새 내부 식별자가 발급되어 위 불변 조건이 깨진다.
비활성 상품은 **공급사에 물어볼 코드 목록에서만** 제외한다.
