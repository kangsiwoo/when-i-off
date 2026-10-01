import type { DayType } from "../api/client";
import { dayTypeLabel } from "../format";
import { DAY_TYPES, toggleDayType, type TargetDraft } from "../target/defaultTarget";

/** 기본 목표 도착 시각(KST)과 추천할 day_type 체크박스 (#68). 경로 생성·설정 폼이 같이 쓴다. */
export function TargetFields({
  idPrefix,
  value,
  onChange,
}: {
  idPrefix: string;
  value: TargetDraft;
  onChange: (next: TargetDraft) => void;
}) {
  const timeId = `${idPrefix}-target-time`;
  return (
    <>
      <div className="field">
        <label htmlFor={timeId}>목표 도착 시각 (KST)</label>
        <input
          id={timeId}
          type="time"
          value={value.time}
          onChange={(e) => onChange({ ...value, time: e.target.value })}
        />
      </div>
      <fieldset className="segmented">
        <legend>추천할 날</legend>
        {DAY_TYPES.map((day: DayType) => (
          <label key={day}>
            <input
              type="checkbox"
              checked={value.dayTypes.includes(day)}
              onChange={() => onChange({ ...value, dayTypes: toggleDayType(value.dayTypes, day) })}
            />
            {dayTypeLabel(day)}
          </label>
        ))}
      </fieldset>
    </>
  );
}
