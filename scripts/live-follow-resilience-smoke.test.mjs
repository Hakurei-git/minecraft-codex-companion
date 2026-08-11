import assert from "node:assert/strict";
import test from "node:test";

import {
  loopbackBase,
  parseCli,
  parseDamageInspection,
  parseFollowInspection,
  runLiveFollowResilienceSmoke,
  validateCleanupStatus,
  validateDamageInspection,
  validateFollowInspection,
  validateRestoredSnapshot,
} from "./live-follow-resilience-smoke.mjs";

test("follow resilience smoke is dry-run by default and loopback-only", async () => {
  assert.equal(loopbackBase("http://127.0.0.1:8765/api").href, "http://127.0.0.1:8765/");
  assert.throws(() => loopbackBase("https://127.0.0.1:8765"));
  assert.throws(() => loopbackBase("http://example.com:8765"));
  const options = parseCli([]);
  assert.equal(options.apply, false);
  assert.equal(options.waitMs, 180_000);
  const report = await runLiveFollowResilienceSmoke(options);
  assert.equal(report.dryRun, true);
  assert.equal(report.reversible, true);
  assert.deepEqual(report.phases, [
    "air-interrupt-cleanup", "ground", "air", "land", "recall",
    "owner-melee", "owner-projectile", "environment",
  ]);
});

test("follow inspection parser requires real ground, flight, landing and recall states", () => {
  const ground = parseFollowInspection(
    "follow-fixture:p=ground,d=4200,v=500,ny=64500,oy=64000,g=0,of=0,og=1,ng=1,s=0,op=1,grav=0,st=0,fm=0,rd=48,aw=0",
  );
  assert.equal(validateFollowInspection(ground, "ground"), ground);
  const air = parseFollowInspection(
    "follow-fixture:p=air,d=5100,v=3200,ny=79000,oy=82200,g=1,of=1,og=0,ng=0,s=0,op=1,grav=1,st=0,fm=1,rd=48,aw=0",
  );
  assert.equal(validateFollowInspection(air, "air"), air);
  const land = parseFollowInspection(
    "follow-fixture:p=land,d=3100,v=0,ny=64000,oy=64000,g=0,of=0,og=1,ng=1,s=0,op=1,grav=0,st=0,fm=0,rd=48,aw=0",
  );
  assert.equal(validateFollowInspection(land, "land"), land);
  const recall = parseFollowInspection(
    "follow-fixture:p=recall,d=7900,v=2000,ny=66000,oy=64000,g=0,of=0,og=1,ng=1,s=0,op=1,grav=0,st=0,fm=0,rd=48,aw=0",
  );
  assert.equal(validateFollowInspection(recall, "recall"), recall);
  assert.throws(() => validateFollowInspection({ ...air, ownerFlying: 0 }, "air"));
  assert.throws(() => validateFollowInspection({ ...ground, distanceMilli: 5_001 }, "ground"));
  assert.throws(() => validateFollowInspection({ ...air, followMode: 0 }, "air"));
  assert.throws(() => validateFollowInspection({ ...ground, ownerCanTeleport: 0 }, "ground"));
  assert.throws(() => validateFollowInspection({ ...ground, stalledTicks: 1 }, "ground"));
  assert.throws(() => validateFollowInspection({ ...ground, recallDistance: 257 }, "ground"));
  assert.throws(() => validateFollowInspection({ ...ground, activeWork: 1 }, "ground"));
  assert.throws(() => parseFollowInspection("follow-fixture:stage=take-off"));
});

test("damage evidence distinguishes owner immunity from environment damage", () => {
  const melee = parseDamageInspection("follow-fixture:x=melee,b=20000,a=20000,ok=0,down=0");
  const projectile = parseDamageInspection("follow-fixture:x=projectile,b=20000,a=20000,ok=0,down=0");
  const environment = parseDamageInspection("follow-fixture:x=environment,b=20000,a=17000,ok=1,down=0");
  assert.equal(validateDamageInspection(melee, "melee"), melee);
  assert.equal(validateDamageInspection(projectile, "projectile"), projectile);
  assert.equal(validateDamageInspection(environment, "environment"), environment);
  assert.throws(() => validateDamageInspection({ ...melee, afterMilli: 19_000 }, "melee"));
  assert.throws(() => validateDamageInspection({ ...environment, afterMilli: 20_000 }, "environment"));
  assert.throws(() => validateDamageInspection({ ...melee, accepted: 1 }, "melee"));
  assert.throws(() => validateDamageInspection({ ...environment, downed: 1 }, "environment"));
});

test("cleanup acknowledgement is exact", () => {
  const restored = "follow-fixture:cleanup restored,dim=1,gm=1,pos=1,stance=1,health=1,grav=1,ability=1,inv=1,ground=1,status=1";
  assert.equal(validateCleanupStatus(restored), restored);
  assert.throws(() => validateCleanupStatus(restored.replace("grav=1", "grav=0")));
  assert.throws(() => validateCleanupStatus("follow-fixture:cleanup restored"));
  assert.throws(() => validateCleanupStatus("follow-fixture:cleanup none"));
});

test("post-cleanup snapshot must match the pre-flight baseline", () => {
  const baseline = {
    dimension: "minecraft:overworld",
    gameMode: "survival",
    stance: "stay",
    health: 13,
    status: "waiting",
    position: { x: 10.5, y: 64, z: -2.5 },
  };
  const restored = {
    ...baseline,
    position: { x: 10.51, y: 64, z: -2.49 },
  };
  assert.equal(validateRestoredSnapshot(baseline, restored), restored);
  assert.throws(() => validateRestoredSnapshot(baseline, { ...restored, gameMode: "creative" }));
  assert.throws(() => validateRestoredSnapshot(baseline, { ...restored, health: 12 }));
  assert.throws(() => validateRestoredSnapshot(baseline, {
    ...restored,
    position: { x: 11.1, y: 64, z: -2.5 },
  }));
  assert.throws(() => validateRestoredSnapshot(baseline, { ...restored, status: "follow-fixture:cleanup restored" }));
});
