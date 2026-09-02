rootProject.name = "stayintegration"

// :app          — 통합 연동 백엔드 본체 (8080)
// :mock-supplier — 공급사 API를 흉내내는 별도 서버 (9090)
//
// Mock을 본체와 같은 프로세스에 두면 자기 자신을 HTTP로 호출하게 되어
// 스레드 고갈이 연동 장애처럼 보이고, 연결 타임아웃 같은 설정도 의미를 잃는다.
// 프로세스를 나누면 그런 사고가 구조적으로 불가능해진다.
include(":app", ":mock-supplier")
