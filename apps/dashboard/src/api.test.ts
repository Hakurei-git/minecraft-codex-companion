import { afterEach, describe, expect, it, vi } from "vitest";
import { fetchSkills, reviewSkill } from "./api.js";

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
