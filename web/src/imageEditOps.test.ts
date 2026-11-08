import { describe, expect, it } from "vitest";
import { IMAGE_EDIT_OPS } from "./extraTools";
import { TOOL_META } from "./toolMeta";

// R109: the web-side op list must stay aligned with Kotlin parseOps / image_edit schema.
const NEW_OPS = ["flip", "rotate_free", "text_draw", "gaussian_blur", "vignette", "auto_enhance"];

describe("R109 image_edit ops alignment", () => {
  it("extraTools.IMAGE_EDIT_OPS lists every R109 op", () => {
    for (const op of NEW_OPS) {
      expect((IMAGE_EDIT_OPS as readonly string[]).includes(op)).toBe(true);
    }
  });

  it("toolMeta.image_edit.ops is aligned with the op list", () => {
    for (const op of NEW_OPS) {
      expect(TOOL_META.image_edit.ops?.includes(op) ?? false).toBe(true);
    }
  });

  it("op names are unique", () => {
    expect(new Set(IMAGE_EDIT_OPS).size).toBe(IMAGE_EDIT_OPS.length);
  });
});
