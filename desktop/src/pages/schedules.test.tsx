import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { setToken } from "../auth/token";
import { detail, route } from "../test/fixtures";
import { json, renderApp } from "../test/render";

beforeEach(() => setToken("devtoken"));

const HEADER = "transit_line_id,transit_stop_id,day_type,direction_code,scheduled_time";

function csvFile(text: string, name = "gtx.csv") {
  return new File([text], name, { type: "text/csv" });
}

async function pickFile(file: File) {
  const input = await screen.findByLabelText("CSV 파일 (UTF-8)");
  fireEvent.change(input, { target: { files: [file] } });
}

/** 경로 목록·상세(이름 표시용)는 fixtures를 돌려준다. 나머지는 `rest`가 처리한다. */
function handler(
  rest: (req: Request, url: URL) => Response | undefined | Promise<Response | undefined>,
) {
  return async (req: Request) => {
    const url = new URL(req.url);
    const handled = await rest(req, url);
    if (handled) return handled;
    if (req.method === "GET" && url.pathname === "/api/v1/commute-routes") return json([route]);
    if (req.method === "GET" && url.pathname === "/api/v1/commute-routes/7") return json(detail);
    throw new Error(`unexpected ${req.method} ${req.url}`);
  };
}

describe("schedules page (#70)", () => {
  it("is reachable from the nav", async () => {
    const { router } = renderApp(
      "/",
      handler(() => undefined),
    );
    fireEvent.click(await screen.findByRole("link", { name: "시간표" }));
    expect(router.state.location.pathname).toBe("/schedules");
    expect(await screen.findByRole("heading", { name: "시간표" })).toBeInTheDocument();
  });

  it("previews a valid file, warns about the replacement scope, uploads multipart and shows the result", async () => {
    let uploaded: File | null = null;
    renderApp(
      "/schedules",
      handler(async (req, url) => {
        if (req.method === "POST" && url.pathname === "/api/v1/admin/schedules/import") {
          const form = await req.formData();
          uploaded = form.get("file") as File;
          return json({ fetched: 4, created: 2, updated: 1, skipped: 1 });
        }
      }),
    );
    const text = [
      HEADER,
      "3,4,WEEKDAY,UP,05:30",
      "3,4,WEEKDAY,UP,05:30:00",
      "3,4,WEEKDAY,UP,05:50",
      "3,5,SATURDAY,DN,06:00",
    ].join("\n");
    await pickFile(csvFile(text));

    const preview = await screen.findByRole("region", { name: "미리보기" });
    expect(preview).toHaveTextContent("데이터 행4행넣을 차편3편");
    expect(within(preview).getByText(/파일 안 중복 시각 1행/)).toBeInTheDocument();
    // 내 경로 구간에서 아는 노선·정류장은 이름으로 (fixtures: 노선 3 = GTX-A, 정류장 4 = 동탄)
    const byLine = within(preview).getByRole("list", { name: "노선별" });
    expect(byLine).toHaveTextContent("GTX-A #3");
    expect(byLine).toHaveTextContent("4행");
    expect(within(preview).getByRole("list", { name: "방향별" })).toHaveTextContent("UP3행DN1행");
    expect(within(preview).getByRole("list", { name: "day_type별" })).toHaveTextContent(
      "평일 (WEEKDAY)3행토요일 (SATURDAY)1행",
    );

    const scope = screen.getByRole("region", { name: "교체 범위" });
    expect(scope).toHaveTextContent("2개 조합(노선·정류장·day_type·방향)");
    const rows = within(scope).getAllByRole("row");
    expect(rows).toHaveLength(3);
    expect(rows[1]).toHaveTextContent("GTX-A #3동탄 #4평일UP2편 (중복 1)05:30 ~ 05:50");

    const button = screen.getByRole("button", { name: "업로드" });
    expect(button).toBeDisabled();
    fireEvent.click(screen.getByLabelText(/기존 시간표를 지우고 이 파일로 바꿉니다/));
    fireEvent.click(button);

    const result = await screen.findByRole("status");
    expect(result).toHaveTextContent("업로드 완료");
    expect(result).toHaveTextContent('{"fetched":4,"created":2,"updated":1,"skipped":1}');
    expect(uploaded).not.toBeNull();
    // 파일 이름은 jsdom FormData → Node Request를 거치며 "blob"이 된다(브라우저에서는 그대로). 서버는 이름을 보지 않는다.
    expect(await uploaded!.text()).toBe(text);

    // 업로드한 첫 조합으로 다음 출발 칸을 채운다.
    const next = screen.getByRole("form", { name: "다음 출발 조회" });
    expect(within(next).getByLabelText("정류장 id")).toHaveValue("4");
    expect(within(next).getByLabelText("방향 코드")).toHaveValue("UP");
    expect(screen.getByRole("region", { name: "다음 출발" })).toHaveTextContent("GTX-A (GTX)");
  });

  it("blocks a file the server would reject and shows the server's message", async () => {
    const { fetch } = renderApp(
      "/schedules",
      handler(() => undefined),
    );
    await pickFile(csvFile(`${HEADER}\n3,4,WEEKDAY,23:30\n`, "old.csv"));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(
      "row 2: expected 5 columns (transit_line_id, transit_stop_id, day_type, direction_code, scheduled_time) but got 4",
    );
    expect(alert).toHaveTextContent("2번째 줄: 3,4,WEEKDAY,23:30");
    expect(screen.queryByRole("button", { name: "업로드" })).not.toBeInTheDocument();
    expect(fetch.mock.calls.some(([r]) => r.method === "POST")).toBe(false);
  });

  it("rejects an empty file", async () => {
    renderApp(
      "/schedules",
      handler(() => undefined),
    );
    await pickFile(csvFile("", "empty.csv"));
    expect(await screen.findByRole("alert")).toHaveTextContent("file must not be empty");
  });

  it("shows the server's 400 detail verbatim", async () => {
    renderApp(
      "/schedules",
      handler((req) =>
        req.method === "POST"
          ? json(
              {
                type: "about:blank",
                title: "Bad Request",
                status: 400,
                detail: "row 2: unknown transit_stop_id 999999",
              },
              400,
            )
          : undefined,
      ),
    );
    await pickFile(csvFile(`${HEADER}\n3,999999,WEEKDAY,UP,05:30\n`));
    fireEvent.click(await screen.findByLabelText(/기존 시간표를 지우고/));
    fireEvent.click(screen.getByRole("button", { name: "업로드" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("서버가 거절했습니다 (HTTP 400 Bad Request)");
    expect(alert).toHaveTextContent("row 2: unknown transit_stop_id 999999");
  });

  it("looks up the next departures with a KST time and shows them", async () => {
    let query: URLSearchParams | null = null;
    renderApp(
      "/schedules",
      handler((_req, url) => {
        if (url.pathname === "/api/v1/transit-lines/search")
          return json([detail.legs[0]!.transitLine]);
        if (url.pathname === "/api/v1/transit-lines/3/schedules/next") {
          query = url.searchParams;
          return json({
            transitLineId: 3,
            stopId: 4,
            directionCode: "UP",
            departures: [
              {
                serviceDate: "2026-09-25",
                dayType: "WEEKDAY",
                scheduledTime: "23:50:00",
                departureAt: "2026-09-25T14:50:00Z",
              },
              {
                serviceDate: "2026-09-26",
                dayType: "SATURDAY",
                scheduledTime: "05:30:00",
                departureAt: "2026-09-25T20:30:00Z",
              },
            ],
          });
        }
      }),
    );
    const section = await screen.findByRole("region", { name: "다음 출발" });
    const s = within(section);
    fireEvent.change(s.getByLabelText("노선 검색"), { target: { value: "GTX" } });
    fireEvent.click(s.getByRole("button", { name: "검색" }));
    fireEvent.click(await s.findByRole("button", { name: "GTX-A" }));

    const form = s.getByRole("form", { name: "다음 출발 조회" });
    const f = within(form);
    fireEvent.click(f.getByRole("button", { name: "조회" }));
    expect(await f.findByRole("alert")).toHaveTextContent("정류장 id를 숫자로 넣으세요.");

    // 내 경로 구간의 정류장이 후보로 뜬다.
    fireEvent.click(f.getByRole("button", { name: "동탄 #4" }));
    fireEvent.change(f.getByLabelText("방향 코드"), { target: { value: " UP " } });
    fireEvent.change(f.getByLabelText("기준 시각 (KST)"), {
      target: { value: "2026-09-25T23:40:00" },
    });
    fireEvent.change(f.getByLabelText("대수"), { target: { value: "2" } });
    fireEvent.click(f.getByRole("button", { name: "조회" }));

    const table = await s.findByRole("table", { name: "다음 출발 목록" });
    expect(query!.get("stopId")).toBe("4");
    expect(query!.get("direction")).toBe("UP");
    expect(query!.get("at")).toBe("2026-09-25T14:40:00Z");
    expect(query!.get("limit")).toBe("2");
    const rows = within(table).getAllByRole("row");
    expect(rows[1]).toHaveTextContent("23:50:002026-09-25평일10분 뒤");
    expect(rows[2]).toHaveTextContent("05:30:00다음 날2026-09-26토요일5시간 50분 뒤");
  });

  it("shows a 404 detail from the next-departures lookup", async () => {
    renderApp(
      "/schedules",
      handler((_req, url) => {
        if (url.pathname === "/api/v1/transit-lines/search")
          return json([detail.legs[0]!.transitLine]);
        if (url.pathname.endsWith("/schedules/next"))
          return json(
            { status: 404, title: "Not Found", detail: "transit stop 99 not found" },
            404,
          );
      }),
    );
    const section = await screen.findByRole("region", { name: "다음 출발" });
    const s = within(section);
    fireEvent.change(s.getByLabelText("노선 검색"), { target: { value: "GTX" } });
    fireEvent.click(s.getByRole("button", { name: "검색" }));
    fireEvent.click(await s.findByRole("button", { name: "GTX-A" }));
    fireEvent.change(s.getByLabelText("정류장 id"), { target: { value: "99" } });
    fireEvent.change(s.getByLabelText("방향 코드"), { target: { value: "UP" } });
    fireEvent.click(s.getByRole("button", { name: "조회" }));
    await waitFor(() =>
      expect(s.getByRole("alert")).toHaveTextContent("transit stop 99 not found"),
    );
  });
});
