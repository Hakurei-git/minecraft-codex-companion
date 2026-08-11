import { mkdirSync, readFileSync, renameSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";
import { taskRecordSchema, type TaskRecord } from "@mc/protocol";
import { z } from "zod";

const VERSION = 1;
const MAX_JOURNAL_BYTES = 16 * 1024 * 1024;
const MAX_TASKS = 512;
const TERMINAL_STATUSES = new Set(["succeeded", "failed", "cancelled"]);

const entrySchema = z.object({
  task: taskRecordSchema,
  owner: z.string().trim().min(1).max(128),
  terminalNotified: z.boolean().optional().default(false),
});
const journalSchema = z.object({
  version: z.literal(VERSION),
  tasks: z.array(entrySchema).max(MAX_TASKS),
});

export interface TaskJournalEntry {
  task: TaskRecord;
  owner: string;
  terminalNotified: boolean;
}

/** Local-only atomic task journal. It deliberately contains no provider credentials. */
export class TaskJournal {
  readonly #statePath: string | null;

  constructor(stateDirectory?: string) {
    this.#statePath = stateDirectory ? path.join(stateDirectory, "task-journal.json") : null;
  }

  load(): TaskJournalEntry[] {
    if (!this.#statePath) return [];
    try {
      if (statSync(this.#statePath).size > MAX_JOURNAL_BYTES) return [];
      const parsed = journalSchema.parse(JSON.parse(readFileSync(this.#statePath, "utf8")));
      return parsed.tasks.map(({ task, owner, terminalNotified }) => ({ task, owner, terminalNotified }));
    } catch {
      return [];
    }
  }

  save(
    tasks: Iterable<TaskRecord>,
    owners: ReadonlyMap<string, string>,
    terminalNotifications: ReadonlySet<string> = new Set(),
  ): void {
    if (!this.#statePath) return;
    const ordered = [...tasks].sort((left, right) => left.createdAt.localeCompare(right.createdAt));
    const unfinished = ordered.filter((task) => !TERMINAL_STATUSES.has(task.status));
    const recentTerminal = ordered
      .filter((task) => TERMINAL_STATUSES.has(task.status))
      .slice(-Math.max(0, MAX_TASKS - unfinished.length));
    const selected = [...unfinished.slice(-MAX_TASKS), ...recentTerminal]
      .slice(-MAX_TASKS)
      .map((task) => ({
        task,
        owner: owners.get(task.id) ?? "local",
        terminalNotified: terminalNotifications.has(task.id),
      }));
    const payload = `${JSON.stringify({ version: VERSION, tasks: selected }, null, 2)}\n`;
    if (Buffer.byteLength(payload, "utf8") > MAX_JOURNAL_BYTES) return;
    try {
      mkdirSync(path.dirname(this.#statePath), { recursive: true });
      const temporary = `${this.#statePath}.${process.pid}.tmp`;
      writeFileSync(temporary, payload, { encoding: "utf8", mode: 0o600 });
      renameSync(temporary, this.#statePath);
    } catch {
      // A read-only or full disk must not stop the in-game companion.
    }
  }
}
