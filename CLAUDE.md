# when-i-off — Claude 작업 메모

## 사용자 용어 (약어)
- **DW** = 다이나믹 워크플로우 (Workflow 도구, 다중 에이전트 오케스트레이션). "DW로 돌려"는 Workflow 도구로 실행하라는 뜻.

## 에이전트 모델 선택
- **개발(코드 작성) 에이전트는 Opus로 돌린다.** Agent 도구는 `model: "opus"`, Workflow 스크립트는 코드 작성 단계의 `agent()`에 `model: 'opus'`를 명시한다.
- 조사/리뷰/문서 등 읽기 위주 작업은 세션 기본 모델을 써도 된다.

## 작업 규칙
- 모든 변경은 `docs/CONVENTIONS.md`의 이슈 → 브랜치 → PR(`Closes #n`) → 리뷰 → Squash merge 흐름을 따른다.
- 설계 문서는 `docs/`. 코드와 설계가 어긋나면 같은 PR에서 문서를 고친다.
- 외부 API 키는 환경변수로만 받는다. 값은 채팅·커밋·로그에 남기지 않는다.
