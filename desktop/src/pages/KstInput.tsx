import { useId } from "react";

/** 브라우저 시간대와 상관없이 KST 벽시계로 읽고 쓰는 datetime-local (변환은 src/kst.ts). */
export function KstInput({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  const id = useId();
  return (
    <div className="field">
      <label htmlFor={id}>{label} (KST)</label>
      <input
        id={id}
        type="datetime-local"
        step="1"
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
    </div>
  );
}
