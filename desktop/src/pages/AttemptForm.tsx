import { useId, useState, type FormEvent } from "react";
import type { AttemptResult, BoardingAttempt } from "../api/client";
import { useUpdateAttempt } from "../api/queries";
import { resultLabel } from "../format";
import { attemptForm, attemptPatch, isEmptyPatch } from "../trips/corrections";
import { KstInput } from "./KstInput";
import { SaveStatus } from "./Status";

const RESULTS: AttemptResult[] = ["CAUGHT", "MISSED", "UNKNOWN"];

export function AttemptForm({ attempt }: { attempt: BoardingAttempt }) {
  const [form, setForm] = useState(() => attemptForm(attempt));
  const [problem, setProblem] = useState<string | null>(null);
  const mutation = useUpdateAttempt(attempt.id);
  const id = useId();

  const submit = (e: FormEvent) => {
    e.preventDefault();
    mutation.reset();
    const result = attemptPatch(attempt, form);
    if (!result.ok) return setProblem(result.message);
    if (isEmptyPatch(result.patch)) return setProblem("바뀐 값이 없습니다.");
    setProblem(null);
    mutation.mutate(result.patch);
  };

  return (
    <form className="form" onSubmit={submit} aria-label={`${attempt.attemptSeq}번째 차 보정`}>
      <KstInput
        label="출발"
        value={form.vehicleActualDepartureAt}
        onChange={(v) => setForm({ ...form, vehicleActualDepartureAt: v })}
      />
      <KstInput
        label="하차"
        value={form.alightedAt}
        onChange={(v) => setForm({ ...form, alightedAt: v })}
      />
      <div className="field">
        <label htmlFor={`${id}-result`}>결과</label>
        <select
          id={`${id}-result`}
          value={form.result}
          onChange={(e) => setForm({ ...form, result: e.target.value as AttemptResult })}
        >
          {RESULTS.map((r) => (
            <option key={r} value={r}>
              {resultLabel(r)}
            </option>
          ))}
        </select>
      </div>
      <div className="field">
        <label htmlFor={`${id}-notes`}>메모</label>
        <textarea
          id={`${id}-notes`}
          rows={2}
          maxLength={2000}
          value={form.notes}
          onChange={(e) => setForm({ ...form, notes: e.target.value })}
        />
      </div>
      <SaveStatus problem={problem} error={mutation.error} saved={mutation.isSuccess} />
      <button type="submit" disabled={mutation.isPending}>
        {mutation.isPending ? "저장 중…" : "시도 저장"}
      </button>
    </form>
  );
}
