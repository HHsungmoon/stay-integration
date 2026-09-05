---
paths:
  - "app/src/main/java/**/dto/**/*.java"
  - "app/src/main/java/**/*Request.java"
  - "app/src/main/java/**/*Response.java"
---

# DTO 작성 규칙

- **`record`가 기본**(Jackson이 record를 네이티브 지원).
  `class`는 상속 · 가변 누적 · 프레임워크가 no-arg를 요구할 때만 예외.
- 엔티티 → DTO 변환은 정적 팩토리 `from(...)`.
- DTO에 `@Data` · `@Builder`를 쓰지 않는다.

## Jackson 3을 쓴다 (조용히 망가지는 함정)

Boot 4의 HTTP 메시지 컨버터가 쓰는 것은 **`tools.jackson.databind`(Jackson 3)**다.
`com.fasterxml.jackson`(2.x)도 전이 의존성으로 클래스패스에 **함께 있어서 잘못 import 해도 컴파일된다.**

그 경우 `JsonNode`가 트리가 아니라 **POJO로 직렬화된다**(`{"array":false,…}`) — 응답이 조용히 망가진다.
공급사 응답 파싱이 이 시스템의 본체이므로 **import를 반드시 확인한다.**

## 검색 응답에 반드시 담기는 것

| 항목 | 비고 |
|---|---|
| 내부 숙소 식별자 · 숙소명 | 공급사 코드가 아니라 **자사 식별자** |
| 내부 객실 타입 식별자 · 객실 타입명 | 동일 |
| 최대 수용 인원 | 객실 1실 기준 |
| 예약 가능 객실 수 | 요청 기간 **전체** 기준. 0이면 예약 불가 |
| 출처 공급사 | |
| 요금 | 아래 2계층 |
| 부분 실패 사실 | 어느 공급사가 실패했는지 |

**요금은 2계층이다** (D-1)

- `price` (필수, 모든 공급사가 채운다) — 통화 · **기간 전체 총액(gross)** · 세금 포함 여부 ·
  조식 포함 여부 · 숙박일수
- `priceDetail` (nullable) — 세금액 · 날짜별 내역. 제공 가능한 공급사만

**계약 조건**: 정렬 · 필터 · 비교 같은 의사결정 로직은 **`price`만으로 가능해야 한다.**
클라이언트가 `priceDetail`에 의존하면 공급사 종속이 응답 계약을 타고 새어 나간다.

1박 평균 단가는 제공하지 않는다. `nights`를 담아 필요한 쪽이 계산하게 한다.
예약 불가 상품은 응답에서 빼지 않고 0으로 노출하며 `available: false`를 병기한다(D-6).

## SupplierResult를 응답 DTO로 직접 쓰지 않는다

sealed interface는 Jackson 다형성 설정이 따로 필요하다.
`SupplierResult`는 내부 모델로만 쓰고, 응답은 `search`의 DTO로 변환한다.
