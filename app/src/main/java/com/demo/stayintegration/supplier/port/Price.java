package com.demo.stayintegration.supplier.port;

// 모든 공급사가 채울 수 있는 공통 분모 — 기간 전체 총액(gross). 정렬·비교는 이것만으로 가능해야 한다.
// nights를 담는 이유(D-3): 1박 평균 단가를 우리가 만들지 않고 필요한 쪽이 계산하게 한다.
public record Price(String currency, long totalAmount, boolean taxIncluded, boolean breakfastIncluded, int nights) {}
