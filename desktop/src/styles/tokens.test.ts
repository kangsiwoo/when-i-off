// 디자인 토큰의 기준은 tokens.css이고 소개 페이지(site/style.css)는 사본이다 (#96).
// 두 파일의 라이트·다크 토큰 값이 같은지 본다. 사이트 전용 레이아웃·글꼴 변수만 예외다.
import siteCss from "../../../site/style.css?raw";
import tokensCss from "./tokens.css?raw";

const SITE_ONLY = new Set(["--sans", "--mono", "--wrap", "--gutter", "--col-gap"]);

const stripComments = (css: string) => css.replace(/\/\*[\s\S]*?\*\//g, "");

/** 첫 `:root { ... }` 블록과 `prefers-color-scheme: dark` 안의 `:root { ... }` 블록의 변수들. */
function tokens(css: string): { light: Map<string, string>; dark: Map<string, string> } {
  const src = stripComments(css);
  const block = (from: number) => {
    const open = src.indexOf(":root {", from);
    if (open < 0) throw new Error(":root 블록이 없다");
    const close = src.indexOf("}", open);
    const vars = new Map<string, string>();
    for (const m of src.slice(open, close).matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) {
      vars.set(m[1]!, m[2]!.replace(/\s+/g, " ").trim());
    }
    return vars;
  };
  const darkAt = src.indexOf("@media (prefers-color-scheme: dark)");
  if (darkAt < 0) throw new Error("다크 블록이 없다");
  return { light: block(0), dark: block(darkAt) };
}

describe("디자인 토큰", () => {
  const app = tokens(tokensCss);
  const site = tokens(siteCss);

  it.each(["light", "dark"] as const)(
    "%s: site/style.css가 tokens.css와 같은 값을 쓴다",
    (mode) => {
      const expected = Object.fromEntries(app[mode]);
      const actual = Object.fromEntries([...site[mode]].filter(([name]) => !SITE_ONLY.has(name)));
      expect(actual).toEqual(expected);
    },
  );

  it("노선색과 앰버 LED가 소개 페이지 값 그대로다", () => {
    expect(app.light.get("--gtx-a")).toBe("#9a6292");
    expect(app.light.get("--bus-red")).toBe("#e60012");
    expect(app.light.get("--line-2")).toBe("#00a84d");
    expect(app.light.get("--suin")).toBe("#f5a200");
    expect(app.light.get("--led")).toBe("#ffb000");
  });
});
