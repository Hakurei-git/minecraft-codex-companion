import { mkdirSync, readFileSync, renameSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";
import {
  AGENT_PROTOCOL_VERSION,
  actionEvidenceSchema,
  facilityRecordSchema,
  goalRecordSchema,
  knowledgeRecordSchema,
  resourceReservationSchema,
  workGraphSchema,
  type ActionEvidence,
  type FacilityRecord,
  type GoalRecord,
  type KnowledgeRecord,
  type ResourceReservation,
  type WorkGraph,
} from "@mc/protocol";
import { z } from "zod";
import { redactSensitiveData } from "./skill-security.js";

const MAX_JOURNAL_BYTES = 24 * 1024 * 1024;
const MAX_GOALS = 512;
const MAX_GRAPHS = 512;
const MAX_FACILITIES = 2_048;
const MAX_RESERVATIONS = 4_096;
const MAX_KNOWLEDGE = 4_096;
const MAX_EVIDENCE = 8_192;

const agentJournalStateSchema = z.object({
  version: z.literal(AGENT_PROTOCOL_VERSION),
  goals: z.array(goalRecordSchema).max(MAX_GOALS).default([]),
  workGraphs: z.array(workGraphSchema).max(MAX_GRAPHS).default([]),
  facilities: z.array(facilityRecordSchema).max(MAX_FACILITIES).default([]),
  reservations: z.array(resourceReservationSchema).max(MAX_RESERVATIONS).default([]),
  knowledge: z.array(knowledgeRecordSchema).max(MAX_KNOWLEDGE).default([]),
  evidence: z.array(actionEvidenceSchema).max(MAX_EVIDENCE).default([]),
});

export type AgentJournalState = z.infer<typeof agentJournalStateSchema>;

export function emptyAgentJournalState(): AgentJournalState {
  return {
    version: AGENT_PROTOCOL_VERSION,
    goals: [],
    workGraphs: [],
    facilities: [],
    reservations: [],
    knowledge: [],
    evidence: [],
  };
}

function latestTimestamp(value: { updatedAt?: string; createdAt?: string; at?: string }): string {
  return value.updatedAt ?? value.createdAt ?? value.at ?? "";
}

function byNewest<T extends { updatedAt?: string; createdAt?: string; at?: string }>(items: readonly T[], limit: number): T[] {
  return [...items]
    .sort((left, right) => latestTimestamp(left).localeCompare(latestTimestamp(right)))
    .slice(-limit);
}

function normalizeState(state: AgentJournalState): AgentJournalState {
  const parsed = agentJournalStateSchema.parse(state);
  const pruned: AgentJournalState = {
    version: AGENT_PROTOCOL_VERSION,
    goals: byNewest(parsed.goals, MAX_GOALS),
    workGraphs: byNewest(parsed.workGraphs, MAX_GRAPHS),
    facilities: byNewest(parsed.facilities, MAX_FACILITIES),
    reservations: byNewest(parsed.reservations, MAX_RESERVATIONS),
    knowledge: byNewest(parsed.knowledge, MAX_KNOWLEDGE),
    evidence: byNewest(parsed.evidence, MAX_EVIDENCE),
  };
  return agentJournalStateSchema.parse(redactSensitiveData(pruned));
}

/** Local-only Agent v2 journal. It stores goals, work graphs, facilities, and evidence, never provider credentials. */
export class AgentJournal {
  readonly #statePath: string | null;

  constructor(stateDirectory?: string) {
    this.#statePath = stateDirectory ? path.join(stateDirectory, "agent-journal.json") : null;
  }

  load(): AgentJournalState {
    if (!this.#statePath) return emptyAgentJournalState();
    try {
      if (statSync(this.#statePath).size > MAX_JOURNAL_BYTES) return emptyAgentJournalState();
      return agentJournalStateSchema.parse(JSON.parse(readFileSync(this.#statePath, "utf8")));
    } catch {
      return emptyAgentJournalState();
    }
  }

  save(state: AgentJournalState): void {
    if (!this.#statePath) return;
    let payload: string;
    try {
      payload = `${JSON.stringify(normalizeState(state), null, 2)}\n`;
    } catch {
      return;
    }
    if (Buffer.byteLength(payload, "utf8") > MAX_JOURNAL_BYTES) return;
    try {
      mkdirSync(path.dirname(this.#statePath), { recursive: true });
      const temporary = `${this.#statePath}.${process.pid}.tmp`;
      writeFileSync(temporary, payload, { encoding: "utf8", mode: 0o600 });
      renameSync(temporary, this.#statePath);
    } catch {
      // Persistence failures must not stop the in-game companion.
    }
  }
}

export type AgentJournalGoal = GoalRecord;
export type AgentJournalWorkGraph = WorkGraph;
export type AgentJournalFacility = FacilityRecord;
export type AgentJournalReservation = ResourceReservation;
export type AgentJournalKnowledge = KnowledgeRecord;
export type AgentJournalEvidence = ActionEvidence;
