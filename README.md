# when-i-off
아 언제 나가야하냐;;;

광역버스/GTX/지하철처럼 배차 간격이 길고 놓치면 손해가 큰 대중교통을, GPS·탑승 기록·
도보 속도·신호등 대기시간을 근거로 "언제 나가야 하는지" 계산해주는 프로젝트.

## 스택

- Backend: Kotlin + Spring
- Analytics: Python
- 데스크탑: TypeScript + React
- 앱: Swift (iOS)

## 설계 문서

backend MVP(#10) 진행 중. 상세한 실행/테스트 방법은 [backend/README.md](backend/README.md).

```bash
cp .env.example .env      # WIO_API_TOKEN은 아무 문자열이면 된다
docker compose up -d --build
curl -s -H "X-Api-Token: $WIO_API_TOKEN" localhost:8080/api/v1/commute-routes
```

Swagger UI는 `localhost:8080/swagger-ui.html`, health는 `localhost:8080/actuator/health`(인증 없음).

- [아키텍처 개요](docs/ARCHITECTURE.md)
- [데이터 모델](docs/DATA_MODEL.md) / [DB 스키마](docs/db/schema.sql)
- [최적 출발 시각 계산 알고리즘](docs/ALGORITHM.md)
- [API 설계](docs/API.md)
- [개발 계획](docs/DEVELOPMENT_PLAN.md) — Phase별 이슈: [#2](../../issues/2) [#3](../../issues/3) [#4](../../issues/4) [#5](../../issues/5) [#6](../../issues/6) [#7](../../issues/7) [#8](../../issues/8)
- [개발 컨벤션](docs/CONVENTIONS.md) — 이슈 → 브랜치 → PR → 리뷰 → Squash merge
