import { describe, expect, it } from "vitest";
import { route } from "../test/fixtures";
import {
  settingsFromRoute,
  settingsPatch,
  targetCreateFields,
  toTimeInput,
  toggleDayType,
  validateTarget,
} from "./defaultTarget";

describe("default target (#68)", () => {
  it("shows the server's HH:mm:ss as HH:mm and an absent time as empty", () => {
    expect(toTimeInput("09:00:00")).toBe("09:00");
    expect(toTimeInput(undefined)).toBe("");
    expect(toTimeInput(null)).toBe("");
  });

  it("keeps day types in enum order when toggling", () => {
    expect(toggleDayType(["SUNDAY_HOLIDAY"], "WEEKDAY")).toEqual(["WEEKDAY", "SUNDAY_HOLIDAY"]);
    expect(toggleDayType(["WEEKDAY", "SATURDAY"], "WEEKDAY")).toEqual(["SATURDAY"]);
  });

  it("validates like the server", () => {
    expect(validateTarget({ time: "", dayTypes: ["WEEKDAY"] })).toBeNull();
    expect(validateTarget({ time: "23:59", dayTypes: ["SATURDAY"] })).toBeNull();
    expect(validateTarget({ time: "24:00", dayTypes: ["WEEKDAY"] })).toMatch("HH:mm");
    expect(validateTarget({ time: "09:00", dayTypes: [] })).toMatch("하나 이상");
  });

  it("omits an empty time from the create request", () => {
    expect(targetCreateFields({ time: "", dayTypes: ["SATURDAY", "WEEKDAY"] })).toEqual({
      defaultTargetDayTypes: ["WEEKDAY", "SATURDAY"],
    });
    expect(targetCreateFields({ time: "08:30", dayTypes: ["WEEKDAY"] })).toEqual({
      defaultTargetArrivalTime: "08:30",
      defaultTargetDayTypes: ["WEEKDAY"],
    });
  });

  it("patches only changed fields and clears the time with the explicit flag", () => {
    const same = settingsFromRoute(route);
    expect(settingsPatch(route, same)).toBeNull();
    expect(settingsPatch(route, { ...same, name: ` ${route.name} ` })).toBeNull();
    expect(settingsPatch(route, { ...same, time: "08:45" })).toEqual({
      defaultTargetArrivalTime: "08:45",
    });
    expect(settingsPatch(route, { ...same, time: "" })).toEqual({
      clearDefaultTargetArrivalTime: true,
    });
    expect(
      settingsPatch(route, {
        ...same,
        name: "주말",
        isActive: false,
        dayTypes: ["SUNDAY_HOLIDAY", "SATURDAY"],
      }),
    ).toEqual({
      name: "주말",
      isActive: false,
      defaultTargetDayTypes: ["SATURDAY", "SUNDAY_HOLIDAY"],
    });
  });
});
