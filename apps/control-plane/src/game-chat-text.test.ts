import { describe, expect, it } from "vitest";
import { sanitizeGameChatText } from "./game-chat-text.js";

describe("sanitizeGameChatText", () => {
  it("preserves normal Chinese chat and removes unsafe control characters", () => {
    expect(sanitizeGameChatText("  好的，马上回来。\u0000  ")).toBe("好的，马上回来。");
  });

  it("rejects highly suspicious question-mark output instead of sending an error into Minecraft", () => {
    expect(() => sanitizeGameChatText("??????,????????????"))
      .toThrowError(expect.objectContaining({ code: "INVALID_GAME_CHAT_TEXT" }));
    expect(sanitizeGameChatText("你确定吗???")).toBe("你确定吗???");
  });

  it("rejects Unicode replacement markers", () => {
    expect(() => sanitizeGameChatText("测试�损坏"))
      .toThrowError(expect.objectContaining({ code: "INVALID_GAME_CHAT_TEXT" }));
  });

  it("rejects an empty reply instead of emitting a synthetic in-game message", () => {
    expect(() => sanitizeGameChatText(" \r\n ")).toThrowError(
      expect.objectContaining({ code: "INVALID_GAME_CHAT_TEXT" }),
    );
  });
});
