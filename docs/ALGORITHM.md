# 최적 출발 시각 계산 알고리즘

Analytics(Python) 모듈이 주기적으로 수행하는 계산을 정의한다. 목표는 "특정 목표 도착
시각(또는 특정 차량)을 맞추기 위해 언제 집을 나서야 하는가"를 확률적으로 답하는 것이다.

## 1. 기본 아이디어

출퇴근 경로는 구간(leg)의 연쇄다.

```
집 --(WALK)--> 정류장 --(BUS)--> 환승지 --(WALK)--> 역 --(GTX)--> 도착역 --(WALK)--> 회사
```

각 구간이 걸리는 시간은 확정값이 아니라 **확률분포**로 다룬다.

- 도보 구간: `user_walking_profile`의 평균/표준편차 (+ 구간에 포함된 신호등 기대 대기시간)
- 탑승 구간: 그 차량이 실제로 정류장/역에 도착하는 시각의 분포 (외부 예측 + 보정된 오차)

전체 경로의 "정류장/역 도착까지 걸리는 시간"은 각 구간 시간의 합이고, 정규분포로 근사하면
평균은 각 평균의 합, 분산은 각 분산의 합이다 (구간별 시간이 서로 독립이라고 가정).

## 2. 구간별 모델

### 2.1 도보 구간 시간 모델

```
walk_time(leg) ~ Normal(mu_walk, sigma_walk^2) + sum(signal_wait_i)
```

- `mu_walk`, `sigma_walk`: `user_walking_profile`에서 조회. 해당 구간 전용 데이터가
  없으면(`sample_count`가 임계치 미달) 사용자의 전역 프로필로 fallback.
- `signal_wait_i`: 그 구간에 속한 각 신호등(`route_leg_signal_crossings` 순서대로)의
  기대 대기시간. 보행자가 신호 도착 시점을 균등분포로 가정하면

  ```
  E[wait] = red_duration^2 / (2 * cycle_duration)
  Var[wait] = (적색 구간에 도착했을 확률 기반의 2차 모멘트, 균등분포 공식 사용)
  ```

  단순화 버전(v1)으로는 `E[wait] = red_duration / 2 * P(적색에 도착)`, 여기서
  `P(적색에 도착) = red_duration / cycle_duration` 를 쓴다. 신호 데이터가 없는 경우
  `traffic_signal_cycles.source = 'USER_OBSERVED'` 값이나, 그마저 없으면 구간별 기본값
  (예: 15초)을 사용한다.

### 2.2 탑승 구간 시간 모델 (특정 차량을 기준으로)

한 차량(예: 오늘 아침 8:03 도착 예정인 버스)에 대해:

```
vehicle_arrival(t) ~ predicted_time(t) + bias(line, stop, time_band)
                      , variance = base_variance + observed_variance(line, stop, time_band)
```

- `predicted_time(t)`: 조회 시점 `t`의 외부 API 실시간 예측, 없으면
  `transit_schedules`의 정적 시간표
- `bias`, `observed_variance`: `transit_arrival_observations`(예측 스냅샷)와
  `boarding_attempts.vehicle_actual_departure_at`(실측)를 노선×정류장×요일유형×시간대로
  묶어서 계산한 (실제 - 예측)의 평균/분산. 샘플이 부족하면 노선 단위로 롤업(rollup)해서
  좁은 그룹의 분산 폭증을 막는다.
- GTX처럼 실시간 API 자체가 없는 경우 `predicted_time`은 항상 정적 시간표값이고,
  `bias`/`variance`는 오로지 실측 기록에서만 학습된다 (콜드스타트 시엔 보수적으로 넓은
  기본 분산을 준다).

## 3. 경로 전체로 합성

목표가 "회사에 목표 도착 시각 `T_target`까지 도착"이라면, 경로를 **뒤에서부터** 역산한다.

1. 마지막 구간(회사까지 도보)의 `walk_time` 분포로부터, 마지막 탑승 구간의 하차역에서
   "이 시각까지는 내려야 한다"는 `T_alight_needed`를 구한다. 목표 확률 `p`(예: 0.95)를
   만족하려면 여유시간을 분포의 `p`-분위수로 잡는다:
   `T_alight_needed = T_target - Quantile(walk_time, p)`
2. 그 시각에 맞는 탑승 구간(예: GTX)의 실제 후보 차량들 중, 도착 예상 시각이
   `T_alight_needed` 이전일 확률이 `p` 이상인 가장 늦은 차량을 고른다.
3. 그 차량을 타려면 승차역에 언제 도착해야 하는지(`T_board_needed`)를 같은 방식으로 구하고,
   그 앞 도보 구간에 대해 반복한다.
4. 맨 앞 구간(집 → 첫 정류장)까지 역산하면 최종적으로 `leave_home_at`이 나온다.

이 과정을 코드로 표현하면 (개념적 pseudocode):

```python
def recommend_departure(route, target_arrival_at, p=0.95):
    needed_at = target_arrival_at
    for leg in reversed(route.legs):
        if leg.type == WALK:
            dist = walk_time_distribution(leg)
            needed_at = needed_at - dist.quantile(p)
        else:  # BUS / SUBWAY / GTX
            candidates = get_candidate_vehicles(leg, before=needed_at)
            vehicle = pick_latest_feasible(candidates, needed_at, p)
            needed_at = vehicle.boarding_time_distribution.quantile(p)  # 승차역 도착 필요 시각
    return needed_at  # == leave_home_at
```

`pick_latest_feasible`: 각 후보 차량에 대해
`P(vehicle_arrival <= needed_at_at_alight) >= p` 를 만족하는 차량 중 가장 늦게(=집에서
가장 늦게 나가도 되는) 출발하는 것을 고른다. 어떤 차량도 만족 못 하면 한 단계 이른 차량으로
내려가며 재시도(=한 대 일찍 타야 한다는 의미)한다.

## 4. 출력

`departure_recommendations`에 다음을 기록한다.

- `recommended_leave_home_at`
- `catch_probability` (선택된 차량 기준 실제 계산된 성공확률, 목표 `p`와 다를 수 있음 —
  후보가 마땅치 않으면 더 낮아질 수도 있음)
- `buffer_seconds` (평균 대비 여유시간 총합, 사용자가 "왜 이 시각인지" 이해하는 데 참고)
- `model_version` (보정 로직이 바뀔 때 과거 추천과 비교 가능하게)

## 5. 모델 캘리브레이션 갱신 주기

- `user_walking_profile`: 매일 배치, 이동 평균(EWMA, 최근 데이터에 더 큰 가중치) 또는
  최근 N개 샘플 윈도우로 갱신. 계절/장비(우천 시 느려짐 등)를 나중에 반영하려면
  `route_leg_id` 대신 `(route_leg_id, weather_condition)` 조합으로 확장 가능(v1 범위 아님).
- 노선별 `bias`/`variance`: 마찬가지로 매일 배치, 최소 샘플 수(예: 5회) 미달 그룹은
  상위 그룹(노선 전체)의 값을 그대로 상속.
- 초기 콜드스타트(기록이 전혀 없을 때)는 `bias=0`, `variance`는 넉넉한 기본값(예: 표준편차
  90초)으로 시작해서 "일단 안전하게" 추천하고, 기록이 쌓일수록 좁아진다.

## 6. v1 범위에서 단순화한 것 (의도적으로 생략)

- 구간 간 독립 가정 (실제로는 "버스가 늦으면 다음 환승도 촘촘해진다" 같은 상관관계가 있을
  수 있으나 v1에서는 무시)
- 날씨/요일 세부 조건 (스키마상 `day_type`만 두고 날씨는 나중 확장 포인트로 남김)
- 정규분포 근사 (실제 분포가 두꺼운 꼬리를 가질 수 있으나, 우선 평균·표준편차만으로 시작하고
  필요해지면 경험적 분포(empirical CDF)로 교체)
