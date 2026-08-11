import { inflateSync } from "node:zlib";

export interface DecodedPng {
  width: number;
  height: number;
  pixels: Uint8Array;
}

const SIGNATURE = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
const MAX_PIXELS = 16_777_216;

function crc32(buffer: Buffer): number {
  let crc = 0xffffffff;
  for (const byte of buffer) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function paeth(left: number, up: number, upperLeft: number): number {
  const prediction = left + up - upperLeft;
  const leftDistance = Math.abs(prediction - left);
  const upDistance = Math.abs(prediction - up);
  const upperLeftDistance = Math.abs(prediction - upperLeft);
  if (leftDistance <= upDistance && leftDistance <= upperLeftDistance) return left;
  return upDistance <= upperLeftDistance ? up : upperLeft;
}

export function decodePng(input: Uint8Array): DecodedPng {
  const buffer = Buffer.from(input);
  if (buffer.length < SIGNATURE.length || !buffer.subarray(0, 8).equals(SIGNATURE)) {
    throw new Error("Reference image must be an 8-bit PNG file");
  }

  let offset = 8;
  let width = 0;
  let height = 0;
  let bitDepth = 0;
  let colorType = -1;
  let palette: Buffer | null = null;
  let transparency: Buffer | null = null;
  const imageParts: Buffer[] = [];
  let ended = false;

  while (offset < buffer.length) {
    if (offset + 12 > buffer.length) throw new Error("Truncated PNG chunk");
    const length = buffer.readUInt32BE(offset);
    const chunkEnd = offset + 12 + length;
    if (chunkEnd > buffer.length) throw new Error("Truncated PNG chunk data");
    const typeBytes = buffer.subarray(offset + 4, offset + 8);
    const type = typeBytes.toString("ascii");
    const data = buffer.subarray(offset + 8, offset + 8 + length);
    const expectedCrc = buffer.readUInt32BE(offset + 8 + length);
    if (crc32(Buffer.concat([typeBytes, data])) !== expectedCrc) throw new Error(`PNG chunk ${type} has an invalid CRC`);

    if (type === "IHDR") {
      if (length !== 13) throw new Error("Invalid PNG IHDR length");
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      bitDepth = data[8] ?? 0;
      colorType = data[9] ?? -1;
      if (data[10] !== 0 || data[11] !== 0 || data[12] !== 0) {
        throw new Error("Only standard non-interlaced PNG files are supported");
      }
    } else if (type === "PLTE") {
      palette = Buffer.from(data);
    } else if (type === "tRNS") {
      transparency = Buffer.from(data);
    } else if (type === "IDAT") {
      imageParts.push(Buffer.from(data));
    } else if (type === "IEND") {
      ended = true;
      break;
    }
    offset = chunkEnd;
  }

  if (!ended || width < 1 || height < 1 || width * height > MAX_PIXELS) throw new Error("PNG dimensions are invalid or too large");
  if (bitDepth !== 8 || ![0, 2, 3, 4, 6].includes(colorType)) throw new Error("Only 8-bit grayscale, RGB, indexed, or RGBA PNG files are supported");
  if (imageParts.length === 0) throw new Error("PNG contains no image data");
  if (colorType === 3 && (!palette || palette.length % 3 !== 0)) throw new Error("Indexed PNG is missing a valid palette");

  const channels = colorType === 0 || colorType === 3 ? 1 : colorType === 2 ? 3 : colorType === 4 ? 2 : 4;
  const stride = width * channels;
  const expectedLength = (stride + 1) * height;
  const packed = inflateSync(Buffer.concat(imageParts), { maxOutputLength: expectedLength });
  if (packed.length !== expectedLength) throw new Error("PNG scanline data has an unexpected length");

  const raw = new Uint8Array(stride * height);
  let packedOffset = 0;
  for (let y = 0; y < height; y += 1) {
    const filter = packed[packedOffset++] ?? -1;
    if (filter < 0 || filter > 4) throw new Error(`Unsupported PNG filter ${filter}`);
    const rowOffset = y * stride;
    for (let x = 0; x < stride; x += 1) {
      const source = packed[packedOffset++] ?? 0;
      const left = x >= channels ? raw[rowOffset + x - channels] ?? 0 : 0;
      const up = y > 0 ? raw[rowOffset + x - stride] ?? 0 : 0;
      const upperLeft = y > 0 && x >= channels ? raw[rowOffset + x - stride - channels] ?? 0 : 0;
      const predictor = filter === 1
        ? left
        : filter === 2
          ? up
          : filter === 3
            ? Math.floor((left + up) / 2)
            : filter === 4
              ? paeth(left, up, upperLeft)
              : 0;
      raw[rowOffset + x] = (source + predictor) & 0xff;
    }
  }

  const pixels = new Uint8Array(width * height * 4);
  for (let index = 0; index < width * height; index += 1) {
    const source = index * channels;
    const destination = index * 4;
    if (colorType === 0) {
      const gray = raw[source] ?? 0;
      pixels.set([gray, gray, gray, 255], destination);
    } else if (colorType === 2) {
      pixels.set([raw[source] ?? 0, raw[source + 1] ?? 0, raw[source + 2] ?? 0, 255], destination);
    } else if (colorType === 3) {
      const paletteIndex = raw[source] ?? 0;
      const paletteOffset = paletteIndex * 3;
      if (!palette || paletteOffset + 2 >= palette.length) throw new Error(`PNG palette index ${paletteIndex} is out of range`);
      pixels.set([
        palette[paletteOffset] ?? 0,
        palette[paletteOffset + 1] ?? 0,
        palette[paletteOffset + 2] ?? 0,
        transparency?.[paletteIndex] ?? 255,
      ], destination);
    } else if (colorType === 4) {
      const gray = raw[source] ?? 0;
      pixels.set([gray, gray, gray, raw[source + 1] ?? 255], destination);
    } else {
      pixels.set([
        raw[source] ?? 0,
        raw[source + 1] ?? 0,
        raw[source + 2] ?? 0,
        raw[source + 3] ?? 255,
      ], destination);
    }
  }
  return { width, height, pixels };
}
