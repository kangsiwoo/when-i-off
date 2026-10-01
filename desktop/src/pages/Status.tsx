import { ApiError } from "../api/client";

export function Loading() {
  return <p className="muted">불러오는 중…</p>;
}

export function ErrorMessage({ error }: { error: Error }) {
  return (
    <p role="alert" className="error">
      불러오지 못했습니다: {error.message}
    </p>
  );
}

/** 저장 실패. backend `detail`을 그대로, 검증 실패면 필드별 `errors[]`도 보여 준다. */
export function SaveError({ error }: { error: Error }) {
  const problem = error instanceof ApiError ? error.problem : undefined;
  const errors =
    typeof problem === "object" && problem !== null && "errors" in problem
      ? problem.errors
      : undefined;
  return (
    <div role="alert" className="error">
      저장하지 못했습니다: {error.message}
      {Array.isArray(errors) && errors.length > 0 && (
        <ul>
          {errors.map((e) => (
            <li key={String(e)}>{String(e)}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** 폼 아래 한 줄: 입력 문제 → 저장 실패 → 저장 성공 순으로 하나만. */
export function SaveStatus({
  problem,
  error,
  saved,
}: {
  problem: string | null;
  error: Error | null;
  saved: boolean;
}) {
  if (problem)
    return (
      <p role="alert" className="error">
        {problem}
      </p>
    );
  if (error) return <SaveError error={error} />;
  if (saved) return <p role="status">저장했습니다.</p>;
  return null;
}
