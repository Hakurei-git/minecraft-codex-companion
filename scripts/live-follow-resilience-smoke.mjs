import { pathToFileURL } from "node:url";

const PHASES = new Set(["ground", "air", "land", "recall"]);

export function loopbackBase(raw) {
  const url = new URL(raw);
  const hostname = url.hostname.toLowerCase();
  if (url.protocol !== "http:" || !["127.0.0.1", "localhost", "::1", "[::1]"].includes(hostname)) {
    throw new Error("live follow resilience smoke only connects to a loopback HTTP service");
  }
  url.pathname = "/";
  url.search = "";
  url.hash = "";
  return url;
}

export function parseCli(argv) {
  const wait = argv.find((value) => value.startsWith("--wait-seconds="));
  const seconds = wait ? Number(wait.slice("--wait-seconds=".length)) : 180;
  if (!Number.isFinite(seconds) || seconds < 30 || seconds > 600) {
    throw new Error("--wait-seconds must be between 30 and 600");
  }
  return {
    apply: argv.includes("--apply"),
    waitMs: seconds * 1_000,
    base: loopbackBase(process.env.MC_COMPANION_URL ?? "http://127.0.0.1:8765"),
  };
}

export function parseFollowInspection(status) {
  const match = /^follow-fixture:p=(ground|air|land|recall),d=(\d+),v=(\d+),ny=(-?\d+),oy=(-?\d+),g=(\d+),of=([01]),og=([01]),ng=([01]),s=(\d+),op=([01]),grav=([01]),st=(\d+),fm=(\d+),rd=(\d+),aw=([01])$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected follow inspection: ${JSON.stringify(status)}`);
  return {
    phase: match[1],
    distanceMilli: Number(match[2]),
    verticalMilli: Number(match[3]),
    npcYMilli: Number(match[4]),
    ownerYMilli: Number(match[5]),
    gameMode: Number(match[6]),
    ownerFlying: Number(match[7]),
    ownerOnGround: Number(match[8]),
    npcOnGround: Number(match[9]),
    stance: Number(match[10]),
    ownerCanTeleport: Number(match[11]),
    npcNoGravity: Number(match[12]),
    stalledTicks: Number(match[13]),
    followMode: Number(match[14]),
    recallDistance: Number(match[15]),
    activeWork: Number(match[16]),
  };
}

export function validateFollowInspection(value, expectedPhase) {
  if (!PHASES.has(expectedPhase) || value.phase !== expectedPhase || value.stance !== 0) {
    throw new Error(`Follow ${expectedPhase} identity failed: ${JSON.stringify(value)}`);
  }
  const expectedFollowMode = expectedPhase === "air" ? 1 : 0;
  const telemetryValid = value.ownerCanTeleport === 1
    && value.recallDistance >= 16 && value.recallDistance <= 256
    && value.followMode === expectedFollowMode
    && value.stalledTicks === 0
    && value.activeWork === 0;
  const valid = expectedPhase === "air"
    ? value.distanceMilli <= 6_000 && value.verticalMilli <= 5_000
      && value.gameMode === 1 && value.ownerFlying === 1
      && value.ownerOnGround === 0 && value.npcOnGround === 0 && value.npcNoGravity === 1
    : value.distanceMilli <= (expectedPhase === "recall" ? 8_000 : 5_000)
      && value.verticalMilli <= (expectedPhase === "recall" ? 4_000 : 3_000)
      && value.gameMode === 0 && value.ownerFlying === 0
      && value.ownerOnGround === 1 && value.npcOnGround === 1 && value.npcNoGravity === 0;
  if (!valid || !telemetryValid) {
    throw new Error(`Follow ${expectedPhase} invariants failed: ${JSON.stringify(value)}`);
  }
  return value;
}

export function parseDamageInspection(status) {
  const match = /^follow-fixture:x=(melee|projectile|environment),b=(\d+),a=(\d+),ok=([01]),down=([01])$/u.exec(status ?? "");
  if (!match) throw new Error(`Unexpected damage inspection: ${JSON.stringify(status)}`);
  return {
    type: match[1],
    beforeMilli: Number(match[2]),
    afterMilli: Number(match[3]),
    accepted: Number(match[4]),
    downed: Number(match[5]),
  };
}

export function validateDamageInspection(value, expectedType) {
  const ownerDamage = expectedType === "melee" || expectedType === "projectile";
  const valid = value.type === expectedType && value.downed === 0
    && value.accepted === (ownerDamage ? 0 : 1)
    && (ownerDamage ? value.afterMilli === value.beforeMilli : value.afterMilli < value.beforeMilli);
  if (!valid) throw new Error(`Damage ${expectedType} invariants failed: ${JSON.stringify(value)}`);
  return value;
}

export function validateCleanupStatus(status) {
  const match = /^follow-fixture:cleanup restored,dim=([01]),gm=([01]),pos=([01]),stance=([01]),health=([01]),grav=([01]),ability=([01]),inv=([01]),ground=([01]),status=([01])$/u.exec(status ?? "");
  if (!match || match.slice(1).some((value) => value !== "1")) {
    throw new Error(`Follow fixture cleanup was not confirmed: ${JSON.stringify(status)}`);
  }
  return status;
}

export function validateRestoredSnapshot(before, after) {
  const beforePosition = before?.position;
  const afterPosition = after?.position;
  const positionRestored = beforePosition && afterPosition
    && Math.abs(beforePosition.x - afterPosition.x) <= 0.5
    && Math.abs(beforePosition.y - afterPosition.y) <= 0.5
    && Math.abs(beforePosition.z - afterPosition.z) <= 0.5;
  const restored = before?.dimension === after?.dimension
    && before?.gameMode === after?.gameMode
    && before?.stance === after?.stance
    && Math.abs(Number(before?.health) - Number(after?.health)) <= 0.01
    && !String(after?.status ?? "").startsWith("follow-fixture:cleanup restored")
    && positionRestored;
  if (!restored) {
    throw new Error(`Follow fixture did not restore its baseline: ${JSON.stringify({ before, after })}`);
  }
  return after;
}

async function request(base, pathname, { method = "GET", body } = {}) {
  const response = await fetch(new URL(pathname, base), {
    method,
    headers: body === undefined ? undefined : { "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(15_000),
  });
  if (!response.ok) {
    throw new Error(`${method} ${pathname} returned HTTP ${response.status}: ${(await response.text()).slice(0, 1_000)}`);
  }
  return response.status === 204 ? null : response.json();
}

async function connectedCompanion(base) {
  const response = await request(base, "/api/companions");
  const companion = response.companions?.find((candidate) => (
    candidate.connected === true && candidate.embodiment === "in-world-npc"
  ));
  if (!companion?.id) throw new Error("No connected Forge in-world NPC was found");
  return companion;
}

async function snapshot(base, companionId) {
  return request(base, `/api/companions/${encodeURIComponent(companionId)}/snapshot`);
}

async function fixture(base, companionId, suite, mode) {
  const before = await snapshot(base, companionId);
  const beforeAck = Number(before.liveFixtureAck?.sequence ?? 0);
  const pathname = `/api/companions/${encodeURIComponent(companionId)}/live-fixtures`;
  await request(base, pathname, { method: "POST", body: { suite, mode } });
  const deadline = Date.now() + 15_000;
  do {
    const current = await snapshot(base, companionId);
    const ack = current.liveFixtureAck;
    if (Number(ack?.sequence ?? 0) > beforeAck && ack?.suite === suite && ack?.mode === mode) {
      if (String(ack.status ?? "").startsWith("live-fixture:denied ")) {
        throw new Error(`Minecraft rejected fixture ${suite}:${mode}: ${ack.status}`);
      }
      if (String(ack.status ?? "").startsWith("live-fixture:failed ")) {
        throw new Error(`Minecraft fixture ${suite}:${mode} failed: ${ack.status}`);
      }
      return { snapshot: current, status: String(ack.status ?? "") };
    }
    await new Promise((resolve) => setTimeout(resolve, 20));
  } while (Date.now() < deadline);
  throw new Error(`Minecraft did not acknowledge fixture ${suite}:${mode}`);
}

async function waitForFollowPhase(options, companionId, phase) {
  const deadline = Date.now() + (phase === "recall" ? Math.min(options.waitMs, 15_000) : options.waitMs);
  let last = null;
  do {
    const result = await fixture(options.base, companionId, "follow", `inspect-${phase}`);
    try {
      return validateFollowInspection(parseFollowInspection(result.status), phase);
    } catch (error) {
      last = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  } while (Date.now() < deadline);
  throw new Error(`Follow ${phase} did not settle: ${last instanceof Error ? last.message : String(last)}`);
}

async function controlFollow(base, companionId) {
  await request(base, `/api/companions/${encodeURIComponent(companionId)}/actions`, {
    method: "POST",
    body: { action: "follow" },
  });
}

async function waitForRestoredSnapshot(options, companionId, baseline) {
  const deadline = Date.now() + 5_000;
  let lastError = null;
  do {
    const current = await snapshot(options.base, companionId);
    try {
      return validateRestoredSnapshot(baseline, current);
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 20));
  } while (Date.now() < deadline);
  throw new Error(`Follow fixture cleanup did not finalize: ${lastError instanceof Error ? lastError.message : String(lastError)}`);
}

async function cleanupFollowFixture(options, companionId, baseline) {
  const evidence = validateCleanupStatus(
    (await fixture(options.base, companionId, "follow", "cleanup")).status,
  );
  const restored = await waitForRestoredSnapshot(options, companionId, baseline);
  return { evidence, finalStatus: restored.status };
}

async function waitForFollowSetup(options, companionId) {
  const deadline = Date.now() + Math.min(options.waitMs, 10_000);
  let lastError = null;
  do {
    try {
      return await fixture(options.base, companionId, "follow", "setup");
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  } while (Date.now() < deadline);
  throw new Error(`Follow fixture setup did not become ready: ${lastError instanceof Error ? lastError.message : String(lastError)}`);
}

async function runFixtureSession(options, companionId, body) {
  const baseline = await snapshot(options.base, companionId);
  let setupSucceeded = false;
  let primaryError = null;
  let value = null;
  let cleanup = null;
  const cleanupErrors = [];
  try {
    await waitForFollowSetup(options, companionId);
    setupSucceeded = true;
    await controlFollow(options.base, companionId);
    value = await body();
  } catch (error) {
    primaryError = error;
  } finally {
    if (setupSucceeded) {
      try {
        await fixture(options.base, companionId, "damage", "cleanup");
      } catch (error) {
        cleanupErrors.push(error);
      }
      try {
        cleanup = await cleanupFollowFixture(options, companionId, baseline);
      } catch (error) {
        cleanupErrors.push(error);
      }
    }
  }

  if (primaryError) {
    const suffix = cleanupErrors.length
      ? `; cleanup: ${cleanupErrors.map((error) => error instanceof Error ? error.message : String(error)).join("; ")}`
      : "";
    throw new Error(`${primaryError instanceof Error ? primaryError.message : String(primaryError)}${suffix}`);
  }
  if (cleanupErrors.length) throw new AggregateError(cleanupErrors, "Follow resilience cleanup failed");
  return { value, cleanup };
}

export async function runLiveFollowResilienceSmoke(options) {
  if (!options.apply) {
    return {
      ok: true,
      dryRun: true,
      localOnly: true,
      reversible: true,
      phases: ["air-interrupt-cleanup", "ground", "air", "land", "recall", "owner-melee", "owner-projectile", "environment"],
    };
  }

  const companion = await connectedCompanion(options.base);
  if (companion.activeTaskId || companion.snapshot?.activeTaskId) {
    throw new Error("Follow resilience smoke requires an idle NPC");
  }
  if (companion.snapshot?.npcDowned) throw new Error("Follow resilience smoke requires an active NPC");

  const interruptedFlight = await runFixtureSession(options, companion.id, async () => {
    await fixture(options.base, companion.id, "follow", "take-off");
    return waitForFollowPhase(options, companion.id, "air");
  });

  const fullRun = await runFixtureSession(options, companion.id, async () => {
    await fixture(options.base, companion.id, "follow", "move-ground");
    const ground = await waitForFollowPhase(options, companion.id, "ground");
    await fixture(options.base, companion.id, "follow", "take-off");
    const air = await waitForFollowPhase(options, companion.id, "air");
    await fixture(options.base, companion.id, "follow", "land");
    const land = await waitForFollowPhase(options, companion.id, "land");
    await fixture(options.base, companion.id, "follow", "far-recall");
    const recall = await waitForFollowPhase(options, companion.id, "recall");

    const melee = validateDamageInspection(parseDamageInspection(
      (await fixture(options.base, companion.id, "damage", "owner-melee")).status,
    ), "melee");
    const projectile = validateDamageInspection(parseDamageInspection(
      (await fixture(options.base, companion.id, "damage", "owner-projectile")).status,
    ), "projectile");
    const environment = validateDamageInspection(parseDamageInspection(
      (await fixture(options.base, companion.id, "damage", "environment")).status,
    ), "environment");

    return { ground, air, land, recall, damage: { melee, projectile, environment } };
  });
  return {
    ok: true,
    dryRun: false,
    localOnly: true,
    reversible: true,
    companionId: companion.id,
    interruptedFlight: {
      air: interruptedFlight.value,
      cleanup: interruptedFlight.cleanup,
    },
    result: fullRun.value,
    cleanup: "restored",
    cleanupEvidence: fullRun.cleanup,
  };
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  runLiveFollowResilienceSmoke(parseCli(process.argv.slice(2))).then(
    (result) => process.stdout.write(`${JSON.stringify(result, null, 2)}\n`),
    (error) => {
      process.stderr.write(`Live follow resilience smoke failed: ${error instanceof Error ? error.message : String(error)}\n`);
      process.exitCode = 1;
    },
  );
}
