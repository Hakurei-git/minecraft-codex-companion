import assert from "node:assert/strict";
import test from "node:test";

import {
  PLAYER_LIFE_ACTOR_EVIDENCE,
  explicitEatingCases,
  fixtureAcknowledged,
  fixturePrefix,
  fixtureUsesOrderedInspection,
  isLowFoodAutomaticEatingSnapshot,
  isExplicitEatingSnapshot,
  loopbackBase,
  parseCli,
  parseFarmInspection,
  parseEatingInspection,
  parseFishingInspection,
  parseGuardInspection,
  parseStateInspection,
  taskSpecs,
  validateCleanup,
  validateFarmInspection,
  validateEatingInspection,
  validateFishingInspection,
  validateGuardInspection,
  validateStateInspection,
} from "./live-player-life-smoke.mjs";

test("player life smoke only accepts loopback HTTP and is dry-run by default", () => {
  assert.equal(loopbackBase("http://127.0.0.1:8765/api/tasks").href, "http://127.0.0.1:8765/");
  assert.equal(loopbackBase("http://localhost:8765").href, "http://localhost:8765/");
  assert.throws(() => loopbackBase("https://localhost:8765"));
  assert.throws(() => loopbackBase("http://192.0.2.1:8765"));
  assert.equal(parseCli([]).apply, false);
  assert.equal(parseCli(["--apply", "--wait-seconds=60"]).waitMs, 60_000);
  assert.throws(() => parseCli(["--wait-seconds=10"]));
});

test("player life smoke assigns exact entity-level tasks", () => {
  const specs = taskSpecs();
  assert.deepEqual(specs.fishing, {
    kind: "fish",
    count: 1,
    radius: 24,
    requestedBy: "live-player-life-smoke",
    note: "Reversible entity-level cast, hook, reel and loot acceptance",
  });
  assert.deepEqual(specs.farmWork, {
    kind: "farm",
    cropId: "minecraft:wheat",
    action: "cycle",
    radius: 8,
    requestedBy: "live-player-life-smoke",
    note: "Reversible mature crop harvest and replant acceptance",
  });
  assert.equal(specs.farmEmpty.action, "harvest");
  assert.equal(specs.guardGather.count, 12);
  assert.equal(specs.guardGather.movement, "walk");
});

test("specified eating cases use exact Minecraft T prompts and selectors", () => {
  const cases = explicitEatingCases();
  assert.deepEqual(cases.map(({ scenario, prompt, itemId, count }) => ({ scenario, prompt, itemId, count })), [
    {
      scenario: "rotten",
      prompt: "把3个腐肉吃掉",
      itemId: "minecraft:rotten_flesh",
      count: 3,
    },
    {
      scenario: "melon",
      prompt: "把2片西瓜吃掉",
      itemId: "minecraft:melon_slice",
      count: 2,
    },
    {
      scenario: "full",
      prompt: "把西瓜吃掉",
      itemId: "minecraft:melon_slice",
      count: 1,
    },
  ]);
  assert.deepEqual(PLAYER_LIFE_ACTOR_EVIDENCE, {
    actor: "ai-npc",
    playerGameplayAssistanceUsed: false,
  });
});

test("fixture acknowledgement prefixes are fixed and cannot accept command text", () => {
  assert.equal(fixturePrefix("player-state", "inspect"), "state-fixture:h=");
  assert.equal(fixturePrefix("eating-action", "inspect"), "eat-fixture:c=");
  assert.equal(fixturePrefix("fishing-action", "setup"), "fish-fixture:setup");
  assert.equal(fixturePrefix("farm-action", "setup-empty"), "farm-fixture:setup-empty");
  assert.equal(fixturePrefix("guard-resume", "release"), "guard-fixture:released");
  assert.throws(() => fixturePrefix("guard-resume", "summon anything"));
});

test("guard arm accepts structured combat preemption when its transient text is overwritten", () => {
  assert.equal(fixtureAcknowledged("guard-resume", "arm", "guard-fixture:armed", 20, {
    liveFixtureAck: { sequence: 21, suite: "guard-resume", mode: "arm", status: "正在保护玩家" },
    activeTaskKind: "combat",
    pausedTaskCount: 1,
  }), true);
  assert.equal(fixtureAcknowledged("guard-resume", "arm", "guard-fixture:armed", 20, {
    liveFixtureAck: { sequence: 21, suite: "guard-resume", mode: "arm", status: "正在采集" },
    activeTaskKind: "gather",
    pausedTaskCount: 0,
  }), false);
  assert.equal(fixtureUsesOrderedInspection("guard-resume", "arm"), true);
  assert.equal(fixtureUsesOrderedInspection("guard-resume", "release"), true);
  assert.equal(fixtureUsesOrderedInspection("guard-resume", "inspect"), false);
});

test("player state inspection proves eating, regeneration and best equipment", () => {
  assert.deepEqual(validateStateInspection(parseStateInspection(
    "state-fixture:h=13000,f=20,e=2,beef=2,managed=0,using=0,dh=1,dc=1,sh=1,regen=1",
  )), {
    healthMilli: 13_000,
    food: 20,
    eaten: 2,
    cookedBeef: 2,
    managedEating: 0,
    usingItem: 0,
    diamondHelmet: 1,
    diamondChestplate: 1,
    shield: 1,
    naturalRegeneration: 1,
  });
  assert.throws(() => validateStateInspection(parseStateInspection(
    "state-fixture:h=12000,f=20,e=2,beef=2,managed=0,using=0,dh=1,dc=1,sh=1,regen=1",
  )));
  assert.throws(() => validateStateInspection(parseStateInspection(
    "state-fixture:h=13000,f=20,e=3,beef=1,managed=0,using=0,dh=1,dc=1,sh=1,regen=1",
  )));
  assert.equal(isLowFoodAutomaticEatingSnapshot({ food: 8, automaticEating: true }), true);
  assert.equal(isLowFoodAutomaticEatingSnapshot({ food: 10, automaticEating: true }), false);
  assert.equal(isLowFoodAutomaticEatingSnapshot({ food: 8, automaticEating: false }), false);
});

test("specified eating inspection proves exact consumption and full-hunger refusal", () => {
  const [rotten, melon, full] = explicitEatingCases();
  validateEatingInspection(parseEatingInspection(
    "eat-fixture:c=rotten,f=10,e=0,r=3,m=2,s=0,x=0,si=none,fi=none,v=0,mg=0,u=0",
  ), rotten, "before");
  validateEatingInspection(parseEatingInspection(
    "eat-fixture:c=rotten,f=20,e=3,r=0,m=2,s=3,x=3,si=rotten,fi=rotten,v=0,mg=0,u=0",
  ), rotten, "after");
  validateEatingInspection(parseEatingInspection(
    "eat-fixture:c=melon,f=20,e=2,r=3,m=0,s=2,x=2,si=melon,fi=melon,v=0,mg=0,u=0",
  ), melon, "after");
  validateEatingInspection(parseEatingInspection(
    "eat-fixture:c=full,f=20,e=0,r=3,m=2,s=0,x=0,si=none,fi=none,v=0,mg=0,u=0",
  ), full, "after");
  assert.throws(() => validateEatingInspection(parseEatingInspection(
    "eat-fixture:c=full,f=20,e=1,r=3,m=1,s=1,x=1,si=melon,fi=melon,v=0,mg=0,u=0",
  ), full, "after"));
  assert.equal(isExplicitEatingSnapshot({
    activeTaskId: "eat-1",
    activeTaskKind: "eat",
    automaticEating: false,
    managedEating: true,
    usingItem: true,
  }, "eat-1"), true);
  assert.equal(isExplicitEatingSnapshot({
    activeTaskId: "eat-1",
    activeTaskKind: "eat",
    automaticEating: false,
    managedEating: true,
    usingItem: false,
  }, "eat-1"), false);
});

test("fishing inspection requires a real owned hook and post-reel loot", () => {
  validateFishingInspection(parseFishingInspection(
    "fish-fixture:hooks=1,owned=1,active=1,loot=0,damage=0",
  ), "cast");
  validateFishingInspection(parseFishingInspection(
    "fish-fixture:hooks=1,owned=1,active=0,loot=1,damage=1",
  ), "caught");
  assert.throws(() => validateFishingInspection(parseFishingInspection(
    "fish-fixture:hooks=1,owned=0,active=1,loot=0,damage=0",
  ), "cast"));
});

test("farm inspection distinguishes real work from zero work", () => {
  validateFarmInspection(parseFarmInspection(
    "farm-fixture:case=work,mature=2,young=0,breaks=0",
  ), "work-before");
  validateFarmInspection(parseFarmInspection(
    "farm-fixture:case=work,mature=0,young=2,breaks=2",
  ), "work-after");
  validateFarmInspection(parseFarmInspection(
    "farm-fixture:case=empty,mature=0,young=0,breaks=0",
  ), "empty");
  assert.throws(() => validateFarmInspection(parseFarmInspection(
    "farm-fixture:case=work,mature=0,young=2,breaks=0",
  ), "work-after"));
});

test("guard inspection proves frozen combat progress and same-task resume", () => {
  validateGuardInspection(parseGuardInspection(
    "guard-fixture:phase=combat,paused=1,pre=250,now=250,hostile=1,same=1,resumed=0,logs=3,breaks=3",
  ), "combat");
  validateGuardInspection(parseGuardInspection(
    "guard-fixture:phase=work,paused=0,pre=250,now=333,hostile=0,same=1,resumed=1,logs=4,breaks=4",
  ), "resumed");
  validateGuardInspection(parseGuardInspection(
    "guard-fixture:phase=work,paused=0,pre=250,now=0,hostile=0,same=0,resumed=1,logs=12,breaks=12",
  ), "completed");
  assert.throws(() => validateGuardInspection(parseGuardInspection(
    "guard-fixture:phase=combat,paused=1,pre=250,now=333,hostile=1,same=1,resumed=0,logs=4,breaks=4",
  ), "combat"));
});

test("cleanup accepts only explicit restored or already-clean acknowledgements", () => {
  assert.equal(validateCleanup("state-fixture:cleanup restored", "player-state"), "state-fixture:cleanup restored");
  assert.equal(validateCleanup("eat-fixture:cleanup restored", "eating-action"), "eat-fixture:cleanup restored");
  assert.equal(validateCleanup("guard-fixture:cleanup none", "guard-resume"), "guard-fixture:cleanup none");
  assert.throws(() => validateCleanup("following", "player-state"));
});
