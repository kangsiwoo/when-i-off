// KST(Asia/Seoul)는 1988년 이후 서머타임이 없어 언제나 UTC+9다. 그래서 브라우저 시간대(Date의 로컬
// 메서드)를 쓰지 않고 UTC 밀리초에 9시간을 더하고 빼는 것만으로 바꾼다 — 어느 시간대의 브라우저에서도
// 같은 결과가 나온다.
const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

const LOCAL_INPUT = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?$/;

/** UTC ISO 시각 → `<input type="datetime-local" step="1">` 값(KST 벽시계, `yyyy-MM-ddTHH:mm:ss`). */
export function isoToKstInput(iso: string | null | undefined): string {
  if (!iso) return "";
  const ms = Date.parse(iso);
  if (Number.isNaN(ms)) return "";
  return new Date(ms + KST_OFFSET_MS).toISOString().slice(0, 19);
}

/**
 * datetime-local 값(KST 벽시계로 읽는다) → UTC ISO(`…Z`, 밀리초 없음). 비었거나 형식이 틀리면 null.
 * 초를 생략한 값(`yyyy-MM-ddTHH:mm`)은 0초로 본다.
 */
export function kstInputToIso(value: string): string | null {
  const m = LOCAL_INPUT.exec(value.trim());
  if (!m) return null;
  const [y, mo, d, h, mi, s] = m.slice(1).map((v) => Number(v ?? 0)) as [
    number,
    number,
    number,
    number,
    number,
    number,
  ];
  const utc = Date.UTC(y, mo - 1, d, h, mi, s) - KST_OFFSET_MS;
  const check = new Date(utc + KST_OFFSET_MS);
  // 2월 30일 같은 값을 Date.UTC가 다음 달로 넘기는 것을 막는다.
  if (check.getUTCMonth() !== mo - 1 || check.getUTCDate() !== d || h > 23 || mi > 59 || s > 59) {
    return null;
  }
  return new Date(utc).toISOString().replace(".000Z", "Z");
}

/** UTC ISO 시각 → KST `HH:mm:ss`. */
export function formatKstTime(iso: string): string {
  return isoToKstInput(iso).slice(11);
}

/** UTC ISO 시각 → KST `yyyy-MM-dd`. */
export function kstDate(iso: string): string {
  return isoToKstInput(iso).slice(0, 10);
}
