import { describe, expect, it } from "vitest";
import { asHttpClientError } from "./http-error.js";

describe("asHttpClientError", () => {
  it("preserves Fastify parser errors as client errors", () => {
    expect(asHttpClientError({
      statusCode: 400,
      code: "FST_ERR_CTP_INVALID_JSON_BODY",
      message: "Body is not valid JSON",
    })).toEqual({ statusCode: 400, message: "Body is not valid JSON" });
  });

  it("does not reclassify server failures", () => {
    expect(asHttpClientError({ statusCode: 500, message: "failed" })).toBeNull();
    expect(asHttpClientError(new Error("failed"))).toBeNull();
  });
});
