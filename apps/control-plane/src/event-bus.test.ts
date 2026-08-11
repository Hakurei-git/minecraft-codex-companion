import { describe, expect, it } from "vitest";
import { CompanionEventBus } from "./event-bus.js";

describe("CompanionEventBus privacy", () => {
  it("redacts credentials and absolute paths before retaining events", () => {
    const bus = new CompanionEventBus();
    const fakeToken = ["sk", "test_secret_value_123456"].join("-");
    const fakePath = ["C:", "Users", "Private", "token.txt"].join("\\");
    const fakeWorldPath = ["C:", "Users", "Private", "world"].join("\\");
    const event = bus.publish({
      type: "warning",
      companionId: null,
      message: `failed with ${fakeToken} at ${fakePath}`,
      data: { apiKey: "secret-value", nested: { file: fakeWorldPath } },
    });
    expect(event.message).not.toContain(fakeToken);
    expect(event.message).not.toContain("C:\\Users");
    expect(event.data).toEqual({ apiKey: "[REDACTED]", nested: { file: "[LOCAL_PATH]" } });
    expect(bus.recent()).toEqual([event]);
  });
});
