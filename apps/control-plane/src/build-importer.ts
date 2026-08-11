import { readFile, stat } from "node:fs/promises";
import path from "node:path";
import {
  buildImportRequestSchema,
  buildPlanDraftSchema,
  type BuildBlock,
  type BuildImageOptions,
  type BuildImportRequest,
  type BuildPlanDraft,
} from "@mc/protocol";
import { parseNbt, type NbtCompound, type NbtValue } from "./nbt-reader.js";
import { assertBuildBlocksSafe } from "./build-safety.js";
import { decodePng } from "./png-reader.js";

const MAX_INPUT_BYTES = 48 * 1024 * 1024;
const MAX_SOURCE_CELLS = 8_000_000;

interface PaletteColor {
  blockId: string;
  red: number;
  green: number;
  blue: number;
}

const DEFAULT_IMAGE_COLORS: Array<[string, string]> = [
  ["minecraft:white_concrete", "#cfd5d6"],
  ["minecraft:light_gray_concrete", "#7d7d73"],
  ["minecraft:gray_concrete", "#36393d"],
  ["minecraft:black_concrete", "#080a0f"],
  ["minecraft:brown_concrete", "#603b2f"],
  ["minecraft:red_concrete", "#8e2121"],
  ["minecraft:orange_concrete", "#e06100"],
  ["minecraft:yellow_concrete", "#f0af15"],
  ["minecraft:lime_concrete", "#5ea919"],
  ["minecraft:green_concrete", "#495b24"],
  ["minecraft:cyan_concrete", "#157788"],
  ["minecraft:light_blue_concrete", "#2389c6"],
  ["minecraft:blue_concrete", "#2c2e8f"],
  ["minecraft:purple_concrete", "#64209c"],
  ["minecraft:magenta_concrete", "#a9309f"],
  ["minecraft:pink_concrete", "#d6658f"],
];

const DEFAULT_IMAGE_PALETTE: PaletteColor[] = DEFAULT_IMAGE_COLORS
  .map(([blockId, color]) => ({ blockId, ...parseHex(color) }));

function parseHex(value: string): Omit<PaletteColor, "blockId"> {
  return {
    red: Number.parseInt(value.slice(1, 3), 16),
    green: Number.parseInt(value.slice(3, 5), 16),
    blue: Number.parseInt(value.slice(5, 7), 16),
  };
}

function normalizeBlockId(value: string): string {
  const trimmed = value.trim();
  if (!trimmed) throw new Error("Block id cannot be empty");
  return trimmed.includes(":") ? trimmed : `minecraft:${trimmed}`;
}

function parseBlockState(value: string): Pick<BuildBlock, "blockId" | "properties"> {
  const match = value.trim().match(/^([^\[]+)(?:\[(.*)\])?$/u);
  const name = match?.[1];
  if (!name) throw new Error(`Invalid block state ${value}`);
  const properties: Record<string, string> = {};
  if (match[2]) {
    for (const entry of match[2].split(",")) {
      const separator = entry.indexOf("=");
      if (separator <= 0) throw new Error(`Invalid block property ${entry}`);
      properties[entry.slice(0, separator).trim()] = entry.slice(separator + 1).trim();
    }
  }
  return { blockId: normalizeBlockId(name), properties };
}

function integer(value: unknown, name: string): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value)) throw new Error(`${name} must be an integer`);
  return value;
}

function compound(value: NbtValue | undefined, name: string): NbtCompound {
  if (!value || typeof value !== "object" || Array.isArray(value) || value instanceof Uint8Array || value instanceof Int32Array) {
    throw new Error(`NBT field ${name} must be a compound`);
  }
  return value as NbtCompound;
}

function optionalCompound(value: NbtValue | undefined): NbtCompound | null {
  if (!value || typeof value !== "object" || Array.isArray(value) || value instanceof Uint8Array || value instanceof Int32Array) return null;
  return value as NbtCompound;
}

function nbtNumber(value: NbtValue | undefined, name: string): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value)) throw new Error(`NBT field ${name} must be an integer`);
  return value;
}

function byteArray(value: NbtValue | undefined, name: string): Uint8Array {
  if (!(value instanceof Uint8Array) || value instanceof Int32Array) throw new Error(`NBT field ${name} must be a byte array`);
  return value;
}

function longArray(value: NbtValue | undefined, name: string): bigint[] {
  if (!Array.isArray(value) || !value.every((item) => typeof item === "bigint")) throw new Error(`NBT field ${name} must be a long array`);
  return value as bigint[];
}

function stringProperties(value: NbtValue | undefined): Record<string, string> {
  const source = optionalCompound(value);
  if (!source) return {};
  return Object.fromEntries(Object.entries(source).map(([key, item]) => {
    if (typeof item !== "string") throw new Error(`Block property ${key} must be a string`);
    return [key, item];
  }));
}

function parseSpongeSchematic(bytes: Uint8Array, includeAir: boolean): BuildBlock[] {
  const parsed = parseNbt(bytes);
  const root = optionalCompound(parsed.Schematic) ?? parsed;
  const blocksRoot = optionalCompound(root.Blocks) ?? root;
  const width = nbtNumber(root.Width, "Width");
  const height = nbtNumber(root.Height, "Height");
  const length = nbtNumber(root.Length, "Length");
  const volume = width * height * length;
  if (width < 1 || height < 1 || length < 1 || !Number.isSafeInteger(volume) || volume > MAX_SOURCE_CELLS) {
    throw new Error("Schematic dimensions are invalid or too large");
  }
  const paletteTag = compound(blocksRoot.Palette ?? root.Palette, "Palette");
  const palette = new Map<number, Pick<BuildBlock, "blockId" | "properties">>();
  for (const [state, id] of Object.entries(paletteTag)) palette.set(nbtNumber(id, `Palette.${state}`), parseBlockState(state));
  const data = byteArray(blocksRoot.Data ?? root.BlockData, "BlockData");
  const states: number[] = [];
  for (let offset = 0; offset < data.length && states.length < volume;) {
    let value = 0;
    let shift = 0;
    while (true) {
      if (offset >= data.length || shift > 28) throw new Error("Schematic BlockData contains an invalid VarInt");
      const byte = data[offset++] ?? 0;
      value |= (byte & 0x7f) << shift;
      if ((byte & 0x80) === 0) break;
      shift += 7;
    }
    states.push(value >>> 0);
  }
  if (states.length !== volume) throw new Error(`Schematic contains ${states.length} block states, expected ${volume}`);
  const blocks: BuildBlock[] = [];
  for (let index = 0; index < volume; index += 1) {
    const state = palette.get(states[index] ?? -1);
    if (!state) throw new Error(`Schematic palette has no state ${(states[index] ?? -1)}`);
    if (!includeAir && state.blockId === "minecraft:air") continue;
    const x = index % width;
    const z = Math.floor(index / width) % length;
    const y = Math.floor(index / (width * length));
    blocks.push({ position: { x, y, z }, ...state });
  }
  return blocks;
}

function vectorFromCompound(value: NbtValue | undefined, name: string): { x: number; y: number; z: number } {
  const source = compound(value, name);
  return {
    x: nbtNumber(source.x ?? source.X, `${name}.x`),
    y: nbtNumber(source.y ?? source.Y, `${name}.y`),
    z: nbtNumber(source.z ?? source.Z, `${name}.z`),
  };
}

function packedValue(values: bigint[], bits: number, index: number): number {
  const start = BigInt(index * bits);
  const arrayIndex = Number(start >> 6n);
  const bitOffset = Number(start & 63n);
  const first = values[arrayIndex];
  if (first === undefined) throw new Error("Litematic BlockStates array is too short");
  let value = BigInt.asUintN(64, first) >> BigInt(bitOffset);
  if (bitOffset + bits > 64) {
    const second = values[arrayIndex + 1];
    if (second === undefined) throw new Error("Litematic BlockStates array ends mid-value");
    value |= BigInt.asUintN(64, second) << BigInt(64 - bitOffset);
  }
  return Number(value & ((1n << BigInt(bits)) - 1n));
}

function parseLitematic(bytes: Uint8Array, includeAir: boolean): BuildBlock[] {
  const root = parseNbt(bytes);
  const regions = compound(root.Regions, "Regions");
  const placed = new Map<string, BuildBlock>();
  let sourceCells = 0;
  for (const [regionName, regionValue] of Object.entries(regions)) {
    const region = compound(regionValue, `Regions.${regionName}`);
    const container = optionalCompound(region.BlockStateContainer) ?? region;
    const position = vectorFromCompound(region.Position, `${regionName}.Position`);
    const signedSize = vectorFromCompound(region.Size, `${regionName}.Size`);
    const size = { x: Math.abs(signedSize.x), y: Math.abs(signedSize.y), z: Math.abs(signedSize.z) };
    const volume = size.x * size.y * size.z;
    sourceCells += volume;
    if (size.x < 1 || size.y < 1 || size.z < 1 || !Number.isSafeInteger(volume) || sourceCells > MAX_SOURCE_CELLS) {
      throw new Error("Litematic dimensions are invalid or too large");
    }
    const paletteList = container.BlockStatePalette ?? container.Palette;
    if (!Array.isArray(paletteList) || paletteList.length < 1) throw new Error(`Litematic region ${regionName} has no palette`);
    const palette = paletteList.map((entry, index) => {
      const state = compound(entry, `${regionName}.Palette[${index}]`);
      if (typeof state.Name !== "string") throw new Error(`Litematic palette entry ${index} has no block name`);
      return { blockId: normalizeBlockId(state.Name), properties: stringProperties(state.Properties) };
    });
    const packed = longArray(container.BlockStates, `${regionName}.BlockStates`);
    const bits = Math.max(2, Math.ceil(Math.log2(palette.length)));
    for (let index = 0; index < volume; index += 1) {
      const stateIndex = palette.length === 1 && packed.length === 0 ? 0 : packedValue(packed, bits, index);
      const state = palette[stateIndex];
      if (!state) throw new Error(`Litematic palette index ${stateIndex} is out of range`);
      if (!includeAir && state.blockId === "minecraft:air") continue;
      const localX = index % size.x;
      const localZ = Math.floor(index / size.x) % size.z;
      const localY = Math.floor(index / (size.x * size.z));
      const block: BuildBlock = {
        position: {
          x: position.x + (signedSize.x < 0 ? -localX : localX),
          y: position.y + (signedSize.y < 0 ? -localY : localY),
          z: position.z + (signedSize.z < 0 ? -localZ : localZ),
        },
        ...state,
      };
      placed.set(`${block.position.x},${block.position.y},${block.position.z}`, block);
    }
  }
  return normalizePositions([...placed.values()]);
}

function objectRecord(value: unknown, name: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(`${name} must be an object`);
  return value as Record<string, unknown>;
}

function jsonPosition(value: unknown, name: string): { x: number; y: number; z: number } {
  if (Array.isArray(value) && value.length >= 3) {
    return { x: integer(value[0], `${name}[0]`), y: integer(value[1], `${name}[1]`), z: integer(value[2], `${name}[2]`) };
  }
  const source = objectRecord(value, name);
  return { x: integer(source.x, `${name}.x`), y: integer(source.y, `${name}.y`), z: integer(source.z, `${name}.z`) };
}

function parseJsonBlocks(bytes: Uint8Array, includeAir: boolean): BuildBlock[] {
  const decoded = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  const parsed = JSON.parse(decoded) as unknown;
  const root = Array.isArray(parsed) ? { blocks: parsed } : objectRecord(parsed, "JSON root");
  const rawBlocks = root.blocks;
  if (!Array.isArray(rawBlocks)) throw new Error("JSON build must contain a blocks array");
  const rawPalette = Array.isArray(root.palette) ? root.palette : null;
  const palette = rawPalette?.map((entry, index) => {
    const state = objectRecord(entry, `palette[${index}]`);
    const name = state.Name ?? state.name ?? state.blockId;
    if (typeof name !== "string") throw new Error(`palette[${index}] has no block name`);
    const propertiesValue = state.Properties ?? state.properties;
    const properties = propertiesValue === undefined
      ? {}
      : Object.fromEntries(Object.entries(objectRecord(propertiesValue, `palette[${index}].properties`)).map(([key, value]) => [key, String(value)]));
    return { blockId: normalizeBlockId(name), properties };
  });
  const blocks = rawBlocks.map((entry, index): BuildBlock => {
    const source = objectRecord(entry, `blocks[${index}]`);
    const position = jsonPosition(source.position ?? source.pos, `blocks[${index}].position`);
    if (palette) {
      const state = palette[integer(source.state, `blocks[${index}].state`)];
      if (!state) throw new Error(`blocks[${index}] refers to a missing palette state`);
      return { position, ...state };
    }
    if (typeof source.blockId !== "string") throw new Error(`blocks[${index}].blockId must be a string`);
    const properties = source.properties === undefined
      ? {}
      : Object.fromEntries(Object.entries(objectRecord(source.properties, `blocks[${index}].properties`)).map(([key, value]) => [key, String(value)]));
    return { position, blockId: normalizeBlockId(source.blockId), properties };
  });
  return normalizePositions(blocks.filter((block) => includeAir || block.blockId !== "minecraft:air"));
}

function normalizePositions(blocks: BuildBlock[]): BuildBlock[] {
  if (blocks.length === 0) return blocks;
  const minimum = blocks.reduce((result, block) => ({
    x: Math.min(result.x, block.position.x),
    y: Math.min(result.y, block.position.y),
    z: Math.min(result.z, block.position.z),
  }), { x: Number.POSITIVE_INFINITY, y: Number.POSITIVE_INFINITY, z: Number.POSITIVE_INFINITY });
  return blocks.map((block) => ({
    ...block,
    position: {
      x: block.position.x - minimum.x,
      y: block.position.y - minimum.y,
      z: block.position.z - minimum.z,
    },
  }));
}

function imagePalette(options: BuildImageOptions): PaletteColor[] {
  if (!options.palette) return DEFAULT_IMAGE_PALETTE;
  const palette = Object.entries(options.palette).map(([blockId, color]) => ({
    blockId: normalizeBlockId(blockId),
    ...parseHex(color),
  }));
  if (palette.length === 0 || palette.length > 256) throw new Error("Image palette must contain 1-256 block colors");
  return palette;
}

function nearestColor(red: number, green: number, blue: number, palette: PaletteColor[]): PaletteColor {
  let best = palette[0]!;
  let bestDistance = Number.POSITIVE_INFINITY;
  for (const candidate of palette) {
    const redDelta = red - candidate.red;
    const greenDelta = green - candidate.green;
    const blueDelta = blue - candidate.blue;
    const distance = redDelta * redDelta * 30 + greenDelta * greenDelta * 59 + blueDelta * blueDelta * 11;
    if (distance < bestDistance) {
      best = candidate;
      bestDistance = distance;
    }
  }
  return best;
}

function parseImage(bytes: Uint8Array, options: BuildImageOptions): BuildBlock[] {
  const image = decodePng(bytes);
  const scale = Math.min(1, options.maxWidth / image.width, options.maxHeight / image.height);
  const width = Math.max(1, Math.round(image.width * scale));
  const height = Math.max(1, Math.round(image.height * scale));
  const palette = imagePalette(options);
  const blocks: BuildBlock[] = [];
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const sourceX = Math.min(image.width - 1, Math.floor((x + 0.5) * image.width / width));
      const sourceY = Math.min(image.height - 1, Math.floor((y + 0.5) * image.height / height));
      const offset = (sourceY * image.width + sourceX) * 4;
      if ((image.pixels[offset + 3] ?? 0) < options.alphaThreshold) continue;
      const color = nearestColor(
        image.pixels[offset] ?? 0,
        image.pixels[offset + 1] ?? 0,
        image.pixels[offset + 2] ?? 0,
        palette,
      );
      blocks.push({
        position: options.plane === "xy" ? { x, y: height - y - 1, z: 0 } : { x, y: 0, z: y },
        blockId: color.blockId,
        properties: {},
      });
    }
  }
  return blocks;
}

async function inputBytes(request: BuildImportRequest): Promise<{ bytes: Buffer; fileName: string }> {
  if (Boolean(request.filePath) === Boolean(request.dataBase64)) {
    throw new Error("Provide exactly one of filePath or dataBase64");
  }
  if (request.filePath) {
    const details = await stat(request.filePath);
    if (!details.isFile() || details.size > MAX_INPUT_BYTES) throw new Error("Build input must be a file no larger than 48 MB");
    return { bytes: await readFile(request.filePath), fileName: request.fileName ?? path.basename(request.filePath) };
  }
  const encoded = request.dataBase64!.replace(/\s+/gu, "");
  if (!/^[A-Za-z0-9+/]*={0,2}$/u.test(encoded)) throw new Error("dataBase64 is invalid");
  const bytes = Buffer.from(encoded, "base64");
  if (bytes.length === 0 || bytes.length > MAX_INPUT_BYTES) throw new Error("Build input must be 1 byte to 48 MB");
  const roundTrip = bytes.toString("base64").replace(/=+$/u, "");
  if (roundTrip !== encoded.replace(/=+$/u, "")) throw new Error("dataBase64 is invalid");
  return { bytes, fileName: request.fileName ?? `build.${request.source === "reference-image" || request.source === "pixel-art" ? "png" : request.source}` };
}

export async function importBuildDraft(input: BuildImportRequest): Promise<BuildPlanDraft> {
  const request = buildImportRequestSchema.parse(input);
  const { bytes, fileName } = await inputBytes(request);
  const extension = path.extname(fileName).toLowerCase();
  let blocks: BuildBlock[];
  if (request.source === "json") {
    if (extension && extension !== ".json") throw new Error("JSON builds must use a .json file");
    blocks = parseJsonBlocks(bytes, request.includeAir);
  } else if (request.source === "schem") {
    if (extension && extension !== ".schem") throw new Error("Sponge schematics must use a .schem file");
    blocks = parseSpongeSchematic(bytes, request.includeAir);
  } else if (request.source === "litematic") {
    if (extension && extension !== ".litematic") throw new Error("Litematica builds must use a .litematic file");
    blocks = parseLitematic(bytes, request.includeAir);
  } else {
    if (extension && extension !== ".png") throw new Error("Pixel art and reference images must be PNG files");
    blocks = parseImage(bytes, request.image);
  }
  if (blocks.length === 0) throw new Error("The imported build contains no blocks after filtering air or transparent pixels");
  assertBuildBlocksSafe(blocks);
  return buildPlanDraftSchema.parse({
    name: request.name,
    source: request.source,
    origin: request.origin,
    blocks,
  });
}
