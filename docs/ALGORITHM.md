# 최적 출발 시각 계산 알고리즘

Analytics(Python) 모듈이 주기적으로 수행하는 계산을 정의한다. 목표는 "특정 목표 도착
시각을 맞추기 위해 언제 집을 나서야 하는가"를 확률적으로 답하는 것이다.

## 1. 기본 아이디어

출퇴근 경로는 구간(leg)의 연쇄다.

```
집 --(WALK)--> 정류장 --(TRANSIT: 버스)--> 환승지 --(WALK)--> 역 --(TRANSIT: GTX)--> 도착역 --(WALK)--> 회사
```

각 구간이 걸리는 시간은 확정값이 아니라 **확률분포**로 다룬다.

- WALK: `user_walking_profile`의 도보 속도 분포 + 구간에 포함된 신호등 대기 분포
- TRANSIT: 그 차량이 승차역에 실제로 도착하는 시각의 분포 (외부 예측 + 보정된 오차)
  와 차내 이동시간 분포

v1에서는 모든 분포를 정규분포로 근사하고 구간끼리 독립이라고 가정한다. 그러면 합의 평균은
평균의 합, 분산은 분산의 합이다.

## 2. 구간별 모델

### 2.1 WALK 구간

거리 `d`(`walking_segments`의 실측 평균, 없으면 `planned_distance_m`), 도보 속도
`S ~ N(μ_s, σ_s²)` (`user_walking_profile`, 구간 전용 행이 없거나 `sample_count`가
임계치 미만이면 전역 행으로 fallback)일 때 순수 도보 시간은 delta method로 근사한다.

```
μ_walk = d / μ_s
σ_walk = d · σ_s / μ_s²
```

여기에 구간에 속한 각 신호등(`route_leg_signal_crossings`)의 대기시간을 더한다. 보행자가
신호 주기 `C` 안의 임의 시점에 균등하게 도착하고 적색 길이가 `R`이면, 대기 `W`는 확률
`(C−R)/C`로 0, 나머지 확률로 `Uniform(0, R)`이므로

```
E[W]   = R² / (2C)
E[W²]  = R³ / (3C)
Var[W] = E[W²] − E[W]²
```

주기 데이터(`traffic_signal_cycles`)가 없는 신호등은 `DEFAULT_ASSUMPTION` 행
(예: C=120초, R=90초 → 평균 약 34초)으로 채워 두고 사용한다.

```
T_walk ~ N( μ_walk + Σ E[W_i] ,  σ_walk² + Σ Var[W_i] )
```

### 2.2 TRANSIT 구간

특정 후보 차량(예: 오늘 8:03 도착 예정인 버스)에 대해 두 개의 확률변수를 둔다.

**승차역 출발 시각 `V_board`**

```
V_board ~ N( predicted_at + bias ,  σ_pred² )
```

- `predicted_at`: 계산 시점의 실시간 예측(`transit_arrival_observations` 최신값).
  `has_realtime_api=false`이거나 예측이 없으면 `transit_schedules`의 시간표값.
- `bias`, `σ_pred`: `transit_prediction_calibration`에서 노선×정류장×요일유형×시간대로
  조회. 샘플 부족 시 노선 단위로 롤업, 그것도 없으면 `bias=0, σ=90초`.

**차내 이동시간 `D`**

```
D ~ N( mean_sec , σ_travel² )      -- transit_travel_time_calibration
```

샘플이 없으면 `route_legs.planned_travel_sec`을 평균으로, 표준편차는 평균의 15% 같은
보수적 기본값을 쓴다.

**하차역 도착 시각**

```
V_alight = V_board + D ~ N( predicted_at + bias + mean_sec ,  σ_pred² + σ_travel² )
```

**후보 차량 목록**: 실시간 API가 있으면 현재 예측된 다음 N대, 없으면 해당 `day_type`
시간표에서 목표 시각 근처 N대.

## 3. 경로 전체를 뒤에서부터 역산

목표 "회사에 `T_target`까지 도착", 목표 성공확률 `p`(예: 0.95).
`needed_at`을 "이 시각까지는 여기에 있어야 한다"로 두고 마지막 구간부터 앞으로 간다.

- **WALK**: 도보 시간이 `p` 확률로 `Q_walk(p)` 이내이므로
  `needed_at ← needed_at − Q_walk(p)`
- **TRANSIT**: 후보 중 `P(V_alight ≤ needed_at) ≥ p`, 즉 `Q_{V_alight}(p) ≤ needed_at`인
  차량 가운데 **가장 늦은** 것을 고른다 (그래야 집에서 가장 늦게 나가도 된다). 그 차를
  `p` 확률로 잡으려면 차가 평소보다 **일찍** 올 경우까지 대비해야 하므로 승차역에는
  `V_board`의 **하위** 분위수까지 도착해야 한다:
  `needed_at ← Q_{V_board}(1 − p)`
  만족하는 차량이 하나도 없으면 후보 창을 앞으로 넓혀(한 대 더 이른 차) 재시도.

맨 앞 구간까지 끝나면 `needed_at`이 곧 `leave_home_at`이다.

```python
def recommend_departure(route, target_arrival_at, p=0.95):
    needed_at = target_arrival_at
    chosen = []
    for leg in reversed(route.legs):
        if leg.type == "WALK":
            needed_at -= walk_time(leg).quantile(p)
        else:
            candidates = candidate_vehicles(leg, around=needed_at)
            feasible = [v for v in candidates if v.alight_dist.quantile(p) <= needed_at]
            vehicle = max(feasible, key=lambda v: v.board_dist.mean)
            chosen.append((leg, vehicle))
            needed_at = vehicle.board_dist.quantile(1 - p)
    return needed_at, chosen
```

`p`는 구간마다 독립적으로 적용되므로 TRANSIT 구간이 k개면 전체 성공확률은 대략 `p^k`다.
"전체 95%"를 원하면 구간별 `p = 0.95^(1/k)`로 올려서 넣는다. v1에서는 구간별 `p`를 그대로
쓰고 결과의 `catch_probability`에 곱한 값을 기록한다.

## 4. 출력

`departure_recommendations`에 기록한다.

- `recommended_leave_home_at` = 위의 최종 `needed_at`
- `catch_probability` = 선택된 각 TRANSIT 구간의 실제 계산된 성공확률의 곱
- `buffer_seconds` = `Σ(분위수 − 평균)`, 즉 평균 소요시간 대비 얹은 여유의 총합
  (사용자가 "왜 이렇게 일찍 나가라는지" 이해하는 데 참고)
- `model_version` = 보정 로직 버전 (바뀔 때 과거 추천과 비교 가능하게)

## 5. 캘리브레이션 갱신

모두 매일 새벽 배치로 갱신한다.

| 대상 | 원재료 | 방법 |
|---|---|---|
| `user_walking_profile` | `walking_segments` | 최근 N회 윈도우 또는 EWMA. 구간별 행은 `sample_count ≥ 5`부터 사용, 그 전엔 전역 행 |
| `transit_prediction_calibration` | attempt의 `vehicle_actual_departure_at − vehicle_scheduled_or_predicted_at` | 그룹별 평균/표준편차. 샘플 5회 미만이면 노선 단위 값을 상속 |
| `transit_travel_time_calibration` | attempt의 `alighted_at − vehicle_actual_departure_at` | 동일 |
| `walking_segments` | trip/attempt의 인접 사건 시각 + `gps_traces` | DATA_MODEL.md의 규칙으로 파생 |

콜드스타트(기록 없음)는 `bias=0`, `σ_pred=90초`, 도보 속도 1.2 m/s ± 0.15 같은 보수적
기본값으로 "일단 안전하게" 추천하고, 기록이 쌓일수록 분포가 좁아져 출발 시각이 뒤로 밀린다.

## 6. v1에서 의도적으로 생략한 것

- 구간 간 독립 가정 ("버스가 늦으면 환승도 촘촘해진다" 같은 상관관계 무시)
- 날씨/계절 조건 (스키마엔 `day_type`만 있음, 필요하면 프로필 키에 추가)
- 정규분포 근사 (실제로는 꼬리가 두꺼울 수 있음. 샘플이 충분해지면 경험적 분포(empirical
  CDF)의 분위수로 교체 가능하도록 `quantile()` 인터페이스만 유지)
- "가장 늦은 차"만 고르는 정책 (한 대 앞 차를 타서 여유를 두는 옵션 등은 UI 설정으로 확장)
