const baseUrl = new URL(process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765");
const owner = "capability-smoke";

async function request(path, { method = "GET", body } = {}) {
  const response = await fetch(new URL(path, baseUrl), {
    method,
    headers: body === undefined ? undefined : { "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(15_000),
  });
  if (!response.ok) {
    throw new Error(`${method} ${path} returned ${response.status}: ${(await response.text()).slice(0, 2_000)}`);
  }
  return response.status === 204 ? null : response.json();
}

const companions = await request("/api/companions");
const companion = companions.companions?.[0];
if (!companion?.id) throw new Error("Control service returned no companion");

const plan = await request("/api/build-plans/preview", {
  method: "POST",
  body: {
    name: "Capability smoke build",
    source: "demo",
    origin: { x: 0, y: 64, z: 0 },
    blocks: [{ position: { x: 0, y: 0, z: 0 }, blockId: "minecraft:stone", properties: {} }],
  },
});
await request(`/api/build-plans/${plan.id}/confirm`, { method: "POST" });

await request("/api/skills/capability-smoke", {
  method: "PUT",
  body: {
    id: "capability-smoke",
    name: "Capability smoke life skill",
    description: "Verifies a declarative multi-step life skill through the runtime queue.",
    parameters: [],
    steps: [{
      label: "Follow the player",
      task: { kind: "follow", player: "PlayerOne", distance: 3, requestedBy: owner },
    }],
  },
});

const specs = [
  { kind: "follow", player: "PlayerOne", distance: 3, requestedBy: owner },
  { kind: "guard", player: "PlayerOne", radius: 12, requestedBy: owner },
  { kind: "move", target: { x: -150, y: 76, z: -60 }, requestedBy: owner },
  { kind: "gather", itemId: "minecraft:oak_log", count: 2, requestedBy: owner },
  { kind: "craft", itemId: "minecraft:oak_planks", count: 4, requestedBy: owner },
  { kind: "smelt", itemId: "minecraft:raw_iron", count: 1, requestedBy: owner },
  { kind: "farm", cropId: "minecraft:wheat", action: "cycle", radius: 8, requestedBy: owner },
  { kind: "store", itemId: "minecraft:oak_log", count: 1, requestedBy: owner },
  { kind: "retrieve", itemId: "minecraft:oak_log", count: 1, requestedBy: owner },
  { kind: "organize-storage", radius: 24, requestedBy: owner },
  { kind: "deliver", itemId: "minecraft:oak_log", count: 1, player: "PlayerOne", requestedBy: owner },
  { kind: "eat", itemId: "minecraft:bread", count: 1, requestedBy: owner },
  { kind: "drop", itemId: "minecraft:bread", count: 1, player: "PlayerOne", requestedBy: owner },
  { kind: "fish", count: 1, radius: 24, requestedBy: owner },
  { kind: "sleep", radius: 32, requestedBy: owner },
  { kind: "explore", radius: 16, direction: "north", requestedBy: owner },
  { kind: "combat", targetType: "minecraft:zombie", maxDistance: 16, requestedBy: owner },
  { kind: "dragon", action: "observe", requestedBy: owner },
  { kind: "build", planId: plan.id, requestedBy: owner },
  { kind: "macro", skillId: "capability-smoke", arguments: {}, requestedBy: owner },
];

const taskIds = [];
for (const spec of specs) {
  const task = await request(`/api/companions/${companion.id}/tasks`, {
    method: "POST",
    body: { spec, owner },
  });
  taskIds.push(task.id);
}

const deadline = Date.now() + 120_000;
let records = [];
while (Date.now() < deadline) {
  const response = await request("/api/tasks");
  records = response.tasks.filter((task) => taskIds.includes(task.id));
  if (records.length === taskIds.length && records.every((task) => ["succeeded", "failed", "cancelled"].includes(task.status))) {
    break;
  }
  await new Promise((resolve) => setTimeout(resolve, 400));
}

records.sort((left, right) => left.createdAt.localeCompare(right.createdAt));
const summary = records.map((task) => ({
  kind: task.spec.kind,
  status: task.status,
  progress: task.progress,
  message: task.message,
  error: task.error?.code ?? null,
}));
console.log(JSON.stringify({ endpoint: baseUrl.href, companion: companion.id, tasks: summary }, null, 2));

if (records.length !== taskIds.length) throw new Error(`Expected ${taskIds.length} tasks, received ${records.length}`);
const failures = records.filter((task) => task.status !== "succeeded");
if (failures.length > 0) throw new Error(`${failures.length} capability tasks did not succeed`);
const completedKinds = new Set(records.map((task) => task.spec.kind));
const missingKinds = specs.map((spec) => spec.kind).filter((kind) => !completedKinds.has(kind));
if (missingKinds.length > 0) throw new Error(`Capability smoke missed task kinds: ${missingKinds.join(", ")}`);
