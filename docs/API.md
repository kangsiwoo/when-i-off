# API 설계 (Backend, Kotlin/Spring)

앱(Swift)과 데스크탑(TS/React)이 공용으로 쓰는 REST API. 아직 구현 전이므로 계약(contract)
초안만 정의한다. 실제 구현 시 OpenAPI 스펙으로 옮기는 것을 권장.

인증은 별도 문서 없이 표준 방식(JWT 등)을 가정하고 생략한다. 모든 엔드포인트는 `/api/v1`
프리픽스를 쓴다고 가정.

## 경로/구간 관리 (데스크탑에서 주로 사용)

| Method | Path | 설명 |
|---|---|---|
| GET | `/commute-routes` | 내 출퇴근 경로 목록 |
| POST | `/commute-routes` | 경로 생성 (이름, 방향) |
| GET | `/commute-routes/{id}` | 경로 상세 (구간 포함) |
| PUT | `/commute-routes/{id}/legs` | 구간 목록 전체 갱신 (순서 재정렬 포함) |
| GET | `/transit-lines/search?mode=BUS&keyword=` | 노선 검색 (구간 등록 시 자동완성용) |
| GET | `/transit-stops/nearby?lat=&lng=&mode=` | 근처 정류장/역 검색 |
| GET | `/traffic-signals/nearby?lat=&lng=` | 근처 신호등 검색 |
| PUT | `/route-legs/{id}/signal-crossings` | 도보 구간의 신호등 순서 등록 |

## 탑승 기록 (앱에서 주로 사용)

| Method | Path | 설명 |
|---|---|---|
| POST | `/boarding-attempts` | 탑승 시도 생성 (leg_id, left_home_at 등 초기값) |
| PATCH | `/boarding-attempts/{id}` | 도착/결과 갱신 (arrived_at_stop_at, result 등) |
| GET | `/boarding-attempts?routeId=&from=&to=` | 이력 조회 (통계/히스토리 화면용) |
| POST | `/gps-traces/batch` | GPS 포인트 배치 업로드 (오프라인 후 재전송 대비) |

앱은 실시간으로 매 GPS 포인트를 보내기보다 일정 주기(예: 10~30초) 또는 위치 변화 임계치마다
배치로 모아 `/gps-traces/batch`에 보내는 것을 권장 (배터리/네트워크 절약).

## 추천 조회

| Method | Path | 설명 |
|---|---|---|
| GET | `/commute-routes/{id}/recommendation?targetArrivalAt=` | 목표 도착 시각 기준 추천 출발 시각 조회 |
| GET | `/commute-routes/{id}/recommendation/latest` | 가장 최근 계산된 추천 (기본 목표 시각 사용) |
| GET | `/commute-routes/{id}/recommendation/history` | 과거 추천과 실제 결과 비교 (모델 성능 확인용) |

`recommendation` 엔드포인트는 Backend가 직접 계산하지 않고, Analytics가 미리 계산해
`departure_recommendations`에 적재해 둔 값을 읽거나(캐시), 캐시가 없으면 즉석 계산을
Analytics 내부 API(`analytics-service:/internal/recommend`)에 위임하는 두 방식을 열어둔다.
초기 구현은 "배치로 미리 계산 + 캐시 조회"만으로 충분하다.

## 외부 데이터 동기화 (Backend 내부 스케줄러, 외부 노출 없음)

- TAGO(국가교통정보센터) 버스 도착정보 API → `transit_arrival_observations`
- 서울 열린데이터광장(or 지자체) 지하철 실시간 API → `transit_arrival_observations`
- 신호운영 데이터(있는 지자체 한정) → `traffic_signal_cycles`
- GTX: 별도 실시간 API 부재 가정 → 정적 시간표만 `transit_schedules`에 수동/반자동 등록

## 캘리브레이션 상태 조회 (데스크탑, 디버깅/신뢰도 확인용)

| Method | Path | 설명 |
|---|---|---|
| GET | `/route-legs/{id}/walking-profile` | 해당 구간의 도보 속도 프로필과 샘플 수 |
| GET | `/transit-lines/{id}/bias?stopId=` | 해당 노선/정류장의 예측 오차 보정치와 샘플 수 |

샘플 수가 적으면 "아직 데이터가 부족해서 추천 신뢰도가 낮다"는 걸 UI에서 보여주기 위함.
