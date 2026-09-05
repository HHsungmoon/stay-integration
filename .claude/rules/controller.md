---
paths:
  - "app/src/main/java/**/controller/**/*.java"
  - "app/src/main/java/**/*Controller.java"
---

# Controller 작성 규칙

- `@RestController @RequiredArgsConstructor @RequestMapping`.
- **얇게 유지한다** — 라우팅 · 입력 검증 · 응답 반환만. 비즈니스는 service로 위임한다.
- repository를 직접 호출하지 않는다.
- 요청 검증은 Request DTO의 Bean Validation + `@Valid`.
- 에러 응답을 직접 조립하지 않는다. **던지기만** 하고 `@RestControllerAdvice`가 처리한다.

## 검색 API 계약

```
GET /api/v1/stays/search?checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0
```

경로와 파라미터 이름은 **이대로 지킨다.**

- 검증 규칙: `checkOut > checkIn`, `adults >= 1`, `children >= 0`, **최대 30박**. 위반 시 400.
- **과거 체크인 날짜는 거부하지 않는다**(D-12). 막으면 "오늘"에 따라 같은 요청의 성패가 갈린다.
- **성공 응답 envelope를 두지 않는다.** 검색 응답 자체가 `status`를 가지므로,
  envelope의 status와 조회 상태가 이중 status가 되어 클라이언트가 두 가지 판정 로직을 갖게 된다.
- 전 공급사 실패도 **HTTP 200 + `ALL_FAILED`**.
  단 `PARTIAL`·`ALL_FAILED` 응답에는 `Cache-Control: no-store`를 붙인다
  (중간 캐시가 빈 결과를 정상 응답으로 캐싱하는 것을 막는다).
- `.block()`은 **여기서 단 한 번** — 톰캣 워커 스레드 위에서만.
