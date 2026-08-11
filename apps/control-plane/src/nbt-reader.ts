import { gunzipSync, inflateSync } from "node:zlib";

export type NbtValue =
  | number
  | bigint
  | string
  | Uint8Array
  | Int32Array
  | bigint[]
  | NbtValue[]
  | NbtCompound;

export interface NbtCompound {
  [key: string]: NbtValue;
}

const MAX_NBT_BYTES = 128 * 1024 * 1024;
const MAX_COLLECTION_LENGTH = 16_000_000;
const MAX_DEPTH = 64;

class NbtReader {
  readonly #buffer: Buffer;
  #offset = 0;

  constructor(buffer: Buffer) {
    this.#buffer = buffer;
  }

  readRoot(): NbtCompound {
    const type = this.#u8();
    if (type !== 10) throw new Error(`NBT root must be a compound tag, got ${type}`);
    this.#string();
    return this.#payload(10, 0) as NbtCompound;
  }

  #payload(type: number, depth: number): NbtValue {
    if (depth > MAX_DEPTH) throw new Error("NBT nesting exceeds 64 levels");
    switch (type) {
      case 1: return this.#i8();
      case 2: return this.#i16();
      case 3: return this.#i32();
      case 4: return this.#i64();
      case 5: return this.#f32();
      case 6: return this.#f64();
      case 7: {
        const length = this.#length();
        return Uint8Array.from(this.#take(length));
      }
      case 8: return this.#string();
      case 9: {
        const childType = this.#u8();
        const length = this.#length();
        const result: NbtValue[] = [];
        for (let index = 0; index < length; index += 1) result.push(this.#payload(childType, depth + 1));
        return result;
      }
      case 10: {
        const result: NbtCompound = {};
        while (true) {
          const childType = this.#u8();
          if (childType === 0) return result;
          const name = this.#string();
          result[name] = this.#payload(childType, depth + 1);
        }
      }
      case 11: {
        const length = this.#length();
        const result = new Int32Array(length);
        for (let index = 0; index < length; index += 1) result[index] = this.#i32();
        return result;
      }
      case 12: {
        const length = this.#length();
        const result: bigint[] = [];
        for (let index = 0; index < length; index += 1) result.push(this.#i64());
        return result;
      }
      default: throw new Error(`Unsupported NBT tag type ${type}`);
    }
  }

  #length(): number {
    const value = this.#i32();
    if (value < 0 || value > MAX_COLLECTION_LENGTH) throw new Error(`Invalid NBT collection length ${value}`);
    return value;
  }

  #string(): string {
    const length = this.#u16();
    return this.#take(length).toString("utf8");
  }

  #take(length: number): Buffer {
    if (!Number.isSafeInteger(length) || length < 0 || this.#offset + length > this.#buffer.length) {
      throw new Error("Unexpected end of NBT data");
    }
    const value = this.#buffer.subarray(this.#offset, this.#offset + length);
    this.#offset += length;
    return value;
  }

  #u8(): number {
    const value = this.#take(1).readUInt8(0);
    return value;
  }

  #i8(): number {
    return this.#take(1).readInt8(0);
  }

  #u16(): number {
    return this.#take(2).readUInt16BE(0);
  }

  #i16(): number {
    return this.#take(2).readInt16BE(0);
  }

  #i32(): number {
    return this.#take(4).readInt32BE(0);
  }

  #i64(): bigint {
    return this.#take(8).readBigInt64BE(0);
  }

  #f32(): number {
    return this.#take(4).readFloatBE(0);
  }

  #f64(): number {
    return this.#take(8).readDoubleBE(0);
  }
}

function decompressNbt(input: Buffer): Buffer {
  if (input.length > MAX_NBT_BYTES) throw new Error("NBT input exceeds 128 MB");
  if (input[0] === 0x1f && input[1] === 0x8b) {
    return gunzipSync(input, { maxOutputLength: MAX_NBT_BYTES });
  }
  if (input[0] === 10) return input;
  try {
    const inflated = inflateSync(input, { maxOutputLength: MAX_NBT_BYTES });
    if (inflated[0] !== 10) throw new Error("Inflated data is not NBT");
    return inflated;
  } catch (error) {
    throw new Error("NBT is neither raw, gzip, nor zlib data", { cause: error });
  }
}

export function parseNbt(input: Uint8Array): NbtCompound {
  const buffer = decompressNbt(Buffer.from(input));
  return new NbtReader(buffer).readRoot();
}
