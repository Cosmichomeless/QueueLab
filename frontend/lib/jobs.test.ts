import { describe, expect, it } from "vitest";
import { JOB_STATUSES } from "./api";
import { canRetry, isActive } from "./jobs";

describe("isActive", () => {
  it.each([
    ["QUEUED", true],
    ["RUNNING", true],
    ["RETRYING", true],
    ["COMPLETED", false],
    ["FAILED", false],
  ] as const)("%s → %s", (status, expected) => {
    expect(isActive(status)).toBe(expected);
  });

  it("cubre todos los estados conocidos", () => {
    expect(JOB_STATUSES.filter(isActive)).toEqual(["QUEUED", "RUNNING", "RETRYING"]);
  });
});

describe("canRetry", () => {
  it("solo permite reintentar un trabajo FAILED", () => {
    expect(JOB_STATUSES.filter((status) => canRetry({ status }))).toEqual(["FAILED"]);
  });
});
