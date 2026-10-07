import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { CommuteTrip } from "../api/client";
import { detail, route, trip } from "../test/fixtures";
import { ticketStamp, ticketTargetAt } from "../trips/ticket";
import { TripTicket } from "./TripTicket";

// 승차권(#96). fixtures의 trip: 집 07:30:00 → 동탄 07:43 차 놓침 → 07:57 차 탐 → 수서 08:18 하차 → 도착 08:25:30 (KST).

const facts = () => within(screen.getByRole("article")).getAllByRole("definition");

describe("TripTicket", () => {
  it("본권에 방향·번호·출발/도착 시각과 구간 띠(놓친 차는 무효 구간)를 그린다", () => {
    render(<TripTicket trip={trip} route={route} legs={detail.legs} />);
    const t = within(screen.getByRole("article", { name: "승차권 2026-09-11 출근" }));
    expect(t.getByText("출근 승차권")).toBeInTheDocument();
    expect(t.getByText("2026-09-11 (금)")).toBeInTheDocument();
    expect(t.getByText("No. 000050")).toBeInTheDocument();
    expect(t.getByText("집")).toBeInTheDocument();
    expect(t.getByText("회사")).toBeInTheDocument();
    expect(t.getByText("55분 30초")).toBeInTheDocument();

    const legs = within(t.getByRole("list", { name: "구간" })).getAllByRole("listitem");
    expect(legs.map((l) => l.className)).toEqual([
      "ticket-leg ticket-leg-walk",
      "ticket-leg ticket-leg-void tone-gtx-a",
      "ticket-leg ticket-leg-transit tone-gtx-a leg-caught",
    ]);
    expect(legs[0]).toHaveTextContent("650 m");
    // 놓친 차: 취소선 + 놓침, 그 차가 떠난 시각
    expect(within(legs[1]!).getByText("1번째 차").tagName).toBe("S");
    expect(legs[1]).toHaveTextContent("07:43 출발");
    expect(legs[2]).toHaveTextContent("동탄 07:57 → 수서 08:18");
  });

  it("보관권: 목표(경로 기본 09:00) 대비 도착과 정시 도장. 추천이 없으면 —", () => {
    render(<TripTicket trip={trip} route={route} legs={detail.legs} />);
    expect(facts().map((d) => d.textContent)).toEqual(["—", "07:30", "09:00", "−35분"]);
    expect(screen.getByText("정시")).toHaveClass("ticket-stamp", "stamp-on_time");
  });

  it("1초라도 늦으면 지각 도장", () => {
    const late: CommuteTrip = { ...trip, arrivedDestinationAt: "2026-09-11T00:05:00Z" };
    render(<TripTicket trip={late} route={route} legs={detail.legs} />);
    expect(facts()[3]).toHaveTextContent("+5분");
    expect(screen.getByText("지각")).toHaveClass("stamp-late");
  });

  it("도착 기록이 없으면 기록 중이고 비는 값은 —", () => {
    const open: CommuteTrip = { ...trip, arrivedDestinationAt: null, boardingAttempts: [] };
    render(<TripTicket trip={open} route={route} legs={detail.legs} />);
    expect(screen.getByText("기록 중")).toHaveClass("stamp-in_progress");
    expect(facts()[3]).toHaveTextContent("—");
    expect(screen.getByText(/탑승 기록 없음/)).toBeInTheDocument();
  });

  it("경로를 모르면 목표·방향을 지어내지 않는다", () => {
    const noLeave: CommuteTrip = { ...trip, leftHomeAt: null };
    render(<TripTicket trip={noLeave} />);
    expect(screen.getByText("이동 승차권")).toBeInTheDocument();
    expect(facts().map((d) => d.textContent)).toEqual(["—", "—", "—", "—"]);
    expect(screen.getByText("도착", { selector: ".ticket-stamp" })).toHaveClass("stamp-arrived");
    expect(screen.getByText("—", { selector: ".ticket-time" })).toBeInTheDocument();
  });

  it("취소 표시와 추천 출발 대비 차이", () => {
    render(
      <TripTicket
        trip={trip}
        route={route}
        legs={detail.legs}
        cancelled
        recommendedLeaveAt="2026-09-10T22:20:00Z"
      />,
    );
    expect(screen.getByText("취소")).toHaveClass("stamp-cancelled");
    expect(facts()[0]).toHaveTextContent("07:20");
    expect(facts()[1]).toHaveTextContent("07:30 +10분");
  });
});

describe("ticket 규칙", () => {
  it("목표 시각은 그날 KST 벽시계", () => {
    expect(ticketTargetAt("2026-09-11", "09:00:00")).toBe("2026-09-11T00:00:00Z");
    expect(ticketTargetAt("2026-09-11", "18:30")).toBe("2026-09-11T09:30:00Z");
    expect(ticketTargetAt("2026-09-11", null)).toBeNull();
  });

  it("도장 순서: 취소 > 기록 중 > 도착(목표 없음) > 지각/정시", () => {
    expect(ticketStamp(null, null, true)).toBe("CANCELLED");
    expect(ticketStamp(null, null)).toBe("IN_PROGRESS");
    expect(ticketStamp("x", null)).toBe("ARRIVED");
    expect(ticketStamp("x", 1)).toBe("LATE");
    expect(ticketStamp("x", 0)).toBe("ON_TIME");
  });
});
