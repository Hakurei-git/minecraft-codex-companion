import { deflateSync, gzipSync } from "node:zlib";
import type { BuildImportRequest } from "@mc/protocol";
import { describe, expect, it } from "vitest";
import { importBuildDraft } from "./build-importer.js";

function u16(value: number): Buffer {
  const result = Buffer.alloc(2);
  result.writeUInt16BE(value);
  return result;
}

function i16(value: number): Buffer {
  const result = Buffer.alloc(2);
  result.writeInt16BE(value);
  return result;
}

function i32(value: number): Buffer {
  const result = Buffer.alloc(4);
  result.writeInt32BE(value);
  return result;
}

function i64(value: bigint): Buffer {
  const result = Buffer.alloc(8);
  result.writeBigInt64BE(value);
  return result;
}

function nbtString(value: string): Buffer {
  const encoded = Buffer.from(value, "utf8");
  return Buffer.concat([u16(encoded.length), encoded]);
}

function tag(type: number, name: string, payload: Buffer): Buffer {
  return Buffer.concat([Buffer.from([type]), nbtString(name), payload]);
}

function compound(...children: Buffer[]): Buffer {
  return Buffer.concat([...children, Buffer.from([0])]);
}

function root(payload: Buffer): Buffer {
  return Buffer.concat([Buffer.from([10]), nbtString(""), payload]);
}

function spongeFixture(): Buffer {
  const palette = compound(
    tag(3, "minecraft:air", i32(0)),
    tag(3, "minecraft:stone", i32(1)),
  );
  return gzipSync(root(compound(
    tag(2, "Width", i16(2)),
    tag(2, "Height", i16(1)),
    tag(2, "Length", i16(1)),
    tag(10, "Palette", palette),
    tag(7, "BlockData", Buffer.concat([i32(2), Buffer.from([1, 0])])),
  )));
}

function litematicFixture(): Buffer {
  const vector = (x: number, y: number, z: number) => compound(
    tag(3, "x", i32(x)),
    tag(3, "y", i32(y)),
    tag(3, "z", i32(z)),
  );
  const paletteEntries = ["minecraft:air", "minecraft:oak_planks"]
    .map((name) => compound(tag(8, "Name", nbtString(name))));
  const palette = Buffer.concat([
    Buffer.from([10]),
    i32(paletteEntries.length),
    ...paletteEntries,
  ]);
  const region = compound(
    tag(10, "Position", vector(4, 5, 6)),
    tag(10, "Size", vector(2, 1, 1)),
    tag(9, "BlockStatePalette", palette),
    tag(12, "BlockStates", Buffer.concat([i32(1), i64(1n)])),
  );
  return gzipSync(root(compound(tag(10, "Regions", compound(tag(10, "main", region))))));
}

function crc32(buffer: Buffer): number {
  let crc = 0xffffffff;
  for (const byte of buffer) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function pngChunk(type: string, data: Buffer): Buffer {
  const name = Buffer.from(type, "ascii");
  const checksum = Buffer.alloc(4);
  checksum.writeUInt32BE(crc32(Buffer.concat([name, data])));
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length);
  return Buffer.concat([length, name, data, checksum]);
}

function pngFixture(): Buffer {
  const header = Buffer.alloc(13);
  header.writeUInt32BE(2, 0);
  header.writeUInt32BE(1, 4);
  header[8] = 8;
  header[9] = 6;
  const scanline = Buffer.from([0, 220, 30, 30, 255, 0, 0, 0, 0]);
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    pngChunk("IHDR", header),
    pngChunk("IDAT", deflateSync(scanline)),
    pngChunk("IEND", Buffer.alloc(0)),
  ]);
}

function request(source: BuildImportRequest["source"], fileName: string, bytes: Buffer): BuildImportRequest {
  return {
    name: `fixture-${source}`,
    source,
    origin: { x: 10, y: 70, z: -5 },
    fileName,
    dataBase64: bytes.toString("base64"),
    includeAir: false,
    image: { plane: "xy", maxWidth: 128, maxHeight: 128, alphaThreshold: 16 },
  };
}

describe("build file importer", () => {
  it("imports normalized and palette-based Minecraft JSON", async () => {
    const bytes = Buffer.from(JSON.stringify({
      palette: [{ Name: "minecraft:stone" }, { Name: "oak_log", Properties: { axis: "y" } }],
      blocks: [{ pos: [3, 2, 1], state: 1 }, { pos: [4, 2, 1], state: 0 }],
    }));
    const draft = await importBuildDraft(request("json", "tiny.json", bytes));
    expect(draft.blocks).toEqual([
      { position: { x: 0, y: 0, z: 0 }, blockId: "minecraft:oak_log", properties: { axis: "y" } },
      { position: { x: 1, y: 0, z: 0 }, blockId: "minecraft:stone", properties: {} },
    ]);
  });

  it("decodes a gzip Sponge .schem palette and VarInts", async () => {
    const draft = await importBuildDraft(request("schem", "tiny.schem", spongeFixture()));
    expect(draft.blocks).toEqual([
      { position: { x: 0, y: 0, z: 0 }, blockId: "minecraft:stone", properties: {} },
    ]);
  });

  it("decodes Litematica regions and packed palette indexes", async () => {
    const draft = await importBuildDraft(request("litematic", "tiny.litematic", litematicFixture()));
    expect(draft.blocks).toEqual([
      { position: { x: 0, y: 0, z: 0 }, blockId: "minecraft:oak_planks", properties: {} },
    ]);
  });

  it("turns opaque PNG pixels into a vertical block palette and skips transparency", async () => {
    const draft = await importBuildDraft(request("pixel-art", "tiny.png", pngFixture()));
    expect(draft.blocks).toHaveLength(1);
    expect(draft.blocks[0]?.position).toEqual({ x: 0, y: 0, z: 0 });
    expect(draft.blocks[0]?.blockId).toBe("minecraft:red_concrete");
  });

  it("rejects ambiguous byte sources", async () => {
    await expect(importBuildDraft({
      ...request("json", "tiny.json", Buffer.from("{}")),
      filePath: "C:/also-present.json",
    })).rejects.toThrow("exactly one");
  });
});
