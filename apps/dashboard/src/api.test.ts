import { afterEach, describe, expect, it, vi } from "vitest";
import {
  advanceAgentGoal,
  fetchAgentFacilities,
  fetchAgentGoals,
  fetchAgentPlan,
  queryAgentKnowledge,
  submitAgentGoal,
  fetchSkills,
  reviewSkill,
} from "./api.js";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("skills review API", () => {
  it("reads the existing skills REST collection", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ skills: [{ id: "life.test" }] }), {
      status: 200,
      headers: { "content-type": "application/json" },
    }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(fetchSkills()).resolves.toEqual([{ id: "life.test" }]);
    expect(fetchMock).toHaveBeenCalledWith("/api/skills", expect.objectContaining({ headers: expect.any(Headers) }));
  });

  it("posts approve and reject decisions to the review route", async () => {
    const reviewed = { id: "external.safe", security: { status: "approved" } };
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(reviewed), {
      status: 200,
      headers: { "content-type": "application/json" },
    }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(reviewSkill("external.safe", true)).resolves.toEqual(reviewed);
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/skills/external.safe/review",
      expect.objectContaining({ method: "POST", body: JSON.stringify({ approved: true }) }),
    );
  });
});

describe("Agent v2 dashboard API", () => {
  it("reads Agent goals, plans, facilities, and local knowledge", async () => {
    const fetchMock = vi.fn((url: string) => {
      if (url === "/api/agent/goals") {
        return Promise.resolve(new Response(JSON.stringify({ goals: [{ id: "goal-1" }] }), { status: 200 }));
      }
      if (url === "/api/agent/goals/goal-1/plan") {
        return Promise.resolve(new Response(JSON.stringify({ id: "plan-1", nodes: [] }), { status: 200 }));
      }
      if (url === "/api/agent/facilities?worldId=world-1") {
        return Promise.resolve(new Response(JSON.stringify({ facilities: [{ id: "facility-1" }] }), { status: 200 }));
      }
      if (url === "/api/agent/knowledge?query=diamond&topic=crafting") {
        return Promise.resolve(new Response(JSON.stringify({ records: [{ id: "knowledge-1" }] }), { status: 200 }));
      }
      return Promise.resolve(new Response("{}", { status: 404 }));
    });
    vi.stubGlobal("fetch", fetchMock);

    await expect(fetchAgentGoals()).resolves.toEqual([{ id: "goal-1" }]);
    await expect(fetchAgentPlan("goal-1")).resolves.toEqual({ id: "plan-1", nodes: [] });
    await expect(fetchAgentFacilities("world-1")).resolves.toEqual([{ id: "facility-1" }]);
    await expect(queryAgentKnowledge("diamond", ["crafting"])).resolves.toEqual([{ id: "knowledge-1" }]);
  });

  it("submits and advances Agent goals through dashboard owner routes", async () => {
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      if (url === "/api/agent/goals") {
        const body = JSON.parse(String(init?.body)) as { companionId: string; owner: string; spec: { objective: string; source: string; metadata: Record<string, string> } };
        expect(body).toMatchObject({
          companionId: "codex-sim",
          owner: "dashboard",
          spec: {
            objective: "制作钻石镐并交给我",
            source: "dashboard",
            metadata: { routedFrom: "dashboard" },
          },
        });
        return Promise.resolve(new Response(JSON.stringify({ id: "goal-2" }), { status: 202 }));
      }
      if (url === "/api/agent/goals/goal-2/advance") {
        expect(init).toMatchObject({
          method: "POST",
          body: JSON.stringify({ owner: "dashboard" }),
        });
        return Promise.resolve(new Response(JSON.stringify({ goal: { id: "goal-2" }, plan: { id: "plan-2" } }), { status: 200 }));
      }
      return Promise.resolve(new Response("{}", { status: 404 }));
    });
    vi.stubGlobal("fetch", fetchMock);

    await expect(submitAgentGoal("codex-sim", "制作钻石镐并交给我", "PlayerOne")).resolves.toEqual({ id: "goal-2" });
    await expect(advanceAgentGoal("goal-2")).resolves.toEqual({ goal: { id: "goal-2" }, plan: { id: "plan-2" } });
  });
});
