# when-i-off — Claude 작업 메모

## 사용자 용어 (약어)
- **DW** = 다이나믹 워크플로우 (Workflow 도구, 다중 에이전트 오케스트레이션). "DW로 돌려"는 Workflow 도구로 실행하라는 뜻.

## 작업 규칙
- 모든 변경은 `docs/CONVENTIONS.md`의 이슈 → 브랜치 → PR(`Closes #n`) → 리뷰 → Squash merge 흐름을 따른다.
- 설계 문서는 `docs/`. 코드와 설계가 어긋나면 같은 PR에서 문서를 고친다.
- 외부 API 키는 환경변수로만 받는다. 값은 채팅·커밋·로그에 남기지 않는다.
