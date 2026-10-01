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
