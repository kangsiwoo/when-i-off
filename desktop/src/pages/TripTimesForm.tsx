import { useState, type FormEvent } from "react";
import type { CommuteTrip } from "../api/client";
import { useUpdateTrip } from "../api/queries";
import { isEmptyPatch, tripPatch, tripTimesForm } from "../trips/corrections";
import { KstInput } from "./KstInput";
import { SaveStatus } from "./Status";

export function TripTimesForm({ trip }: { trip: CommuteTrip }) {
  const [form, setForm] = useState(() => tripTimesForm(trip));
  const [problem, setProblem] = useState<string | null>(null);
  const mutation = useUpdateTrip(trip.id);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    mutation.reset();
    const result = tripPatch(trip, form);
    if (!result.ok) return setProblem(result.message);
    if (isEmptyPatch(result.patch)) return setProblem("바뀐 값이 없습니다.");
    setProblem(null);
    mutation.mutate(result.patch);
  };

  return (
    <form className="card form" onSubmit={submit} aria-label="trip 시각 보정">
      <KstInput
        label="집 나섬"
        value={form.leftHomeAt}
        onChange={(v) => setForm({ ...form, leftHomeAt: v })}
      />
      <KstInput
        label="도착"
        value={form.arrivedDestinationAt}
        onChange={(v) => setForm({ ...form, arrivedDestinationAt: v })}
      />
      <SaveStatus problem={problem} error={mutation.error} saved={mutation.isSuccess} />
      <button type="submit" disabled={mutation.isPending}>
        {mutation.isPending ? "저장 중…" : "시각 저장"}
      </button>
    </form>
  );
}
