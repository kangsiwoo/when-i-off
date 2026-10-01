import { useState, type FormEvent } from "react";
import type { CommuteRoute } from "../api/client";
import { useUpdateRoute } from "../api/queries";
import { settingsFromRoute, settingsPatch, validateTarget } from "../target/defaultTarget";
import { SaveStatus } from "./Status";
import { TargetFields } from "./TargetFields";

/**
 * 경로 설정 (#68): 이름·사용 여부·기본 목표 도착 시각·추천할 날. 바뀐 필드만 PATCH로 보낸다.
 * analytics `recommend --all-active-routes`가 사용 중인 경로마다 이 시각으로 추천을 계산한다.
 */
export function RouteSettingsForm({ route }: { route: CommuteRoute }) {
  const update = useUpdateRoute(route.id);
  const [draft, setDraft] = useState(() => settingsFromRoute(route));
  const [problem, setProblem] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  const change = (next: typeof draft) => {
    setSaved(false);
    setProblem(null);
    setDraft(next);
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (draft.name.trim() === "") return setProblem("이름을 넣으세요.");
    const targetProblem = validateTarget(draft);
    if (targetProblem) return setProblem(targetProblem);
    const body = settingsPatch(route, draft);
    if (!body) return setSaved(true);
    update.mutate(body, { onSuccess: () => setSaved(true) });
  };

  return (
    <form className="card form" onSubmit={submit} aria-label="경로 설정">
      <div className="field">
        <label htmlFor="settings-name">이름</label>
        <input
          id="settings-name"
          value={draft.name}
          onChange={(e) => change({ ...draft, name: e.target.value })}
        />
      </div>
      <label className="checkbox">
        <input
          type="checkbox"
          checked={draft.isActive}
          onChange={(e) => change({ ...draft, isActive: e.target.checked })}
        />
        사용 중
      </label>
      <TargetFields
        idPrefix="settings"
        value={draft}
        onChange={(t) => change({ ...draft, ...t })}
      />
      <button type="submit" disabled={update.isPending}>
        저장
      </button>
      <SaveStatus problem={problem} error={update.error} saved={saved} />
      <p className="muted small form-note">
        목표 시각을 비우면 일괄 추천(cron)에서 빠집니다. 추천은 목표 120분 전부터 30분 뒤까지
        10분마다 다시 계산됩니다.
      </p>
    </form>
  );
}
