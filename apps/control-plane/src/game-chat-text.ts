const CONTROL_CHARACTERS = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/gu;
const REPLACEMENT_MARKERS = /\uFFFD|锟斤拷/u;

export class InvalidGameChatTextError extends Error {
  readonly code = "INVALID_GAME_CHAT_TEXT";
  readonly suggestedRecovery = "Regenerate the same concise reply as valid Unicode text and call mc_chat exactly once.";

  constructor(reason: "empty" | "encoding") {
    super(reason === "empty"
      ? "Minecraft chat reply is empty"
      : "Minecraft chat reply contains invalid or corrupted encoding");
    this.name = "InvalidGameChatTextError";
  }
}

export function sanitizeGameChatText(input: string): string {
  const normalized = input
    .normalize("NFC")
    .replace(/\r\n?/gu, "\n")
    .replace(CONTROL_CHARACTERS, "")
    .trim();
  if (!normalized) throw new InvalidGameChatTextError("empty");
  if (REPLACEMENT_MARKERS.test(normalized)) throw new InvalidGameChatTextError("encoding");

  const visible = [...normalized].filter((character) => !/\s/u.test(character));
  const questionMarks = visible.filter((character) => character === "?" || character === "？").length;
  if (visible.length >= 8 && questionMarks >= 6 && questionMarks / visible.length >= 0.5) {
    throw new InvalidGameChatTextError("encoding");
  }
  return normalized;
}
