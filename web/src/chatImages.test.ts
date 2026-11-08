import { beforeEach, describe, expect, it } from "vitest";
import { chatImageUrl, hydrateChatImages, imagesOfMessage } from "./chatImages";
import { visionCapable } from "./modelConfig";

// R88: 图片附件在消息里只留相对路径；base64 只在发给模型前按路径补水。

const installBridge = (base64: (path: string) => string) => {
  (window as unknown as { AndroidBridge?: unknown }).AndroidBridge = {
    chatImageBase64: base64,
  };
};

beforeEach(() => {
  delete (window as unknown as { AndroidBridge?: unknown }).AndroidBridge;
});

describe("chatImageUrl", () => {
  it("把相对路径映射到 /local/ 只读路由", () => {
    expect(chatImageUrl("chat-images/a.jpg")).toBe("/local/chat-images/a.jpg");
  });

  it("已经是 data:/http(s)/绝对路径的直接透传", () => {
    expect(chatImageUrl("data:image/jpeg;base64,AAA")).toBe("data:image/jpeg;base64,AAA");
    expect(chatImageUrl("https://x/y.png")).toBe("https://x/y.png");
    expect(chatImageUrl("/local/chat-images/a.jpg")).toBe("/local/chat-images/a.jpg");
  });
});

describe("hydrateChatImages", () => {
  it("按路径补 base64 与 mimeType，消息本体不被修改", () => {
    installBridge((path) => (path === "chat-images/a.jpg" ? "QUJD" : ""));
    const message = {
      role: "user",
      content: [
        { type: "text", text: "这是什么" },
        { type: "image", path: "chat-images/a.jpg", mimeType: "image/jpeg" },
      ],
    };
    const [hydrated] = hydrateChatImages([message]);
    expect(hydrated.content).toEqual([
      { type: "text", text: "这是什么" },
      { type: "image", data: "QUJD", mimeType: "image/jpeg" },
    ]);
    // 原始消息仍是路径形态（持久化只存路径）。
    expect((message.content[1] as { data?: string }).data).toBeUndefined();
  });

  it("读不到图时降级成一句说明，不把空图发给模型", () => {
    installBridge(() => "");
    const [hydrated] = hydrateChatImages([
      { role: "user", content: [{ type: "image", path: "chat-images/gone.jpg" }] },
    ]);
    const parts = hydrated.content as Array<{ type: string; text?: string }>;
    expect(parts).toHaveLength(1);
    expect(parts[0].type).toBe("text");
    expect(parts[0].text).toBeTruthy();
  });

  it("字符串 content（无图消息）原样返回", () => {
    const messages = [{ role: "user", content: "纯文本" }];
    expect(hydrateChatImages(messages)[0].content).toBe("纯文本");
  });
});

describe("imagesOfMessage", () => {
  it("取出消息里的图片附件（重试/编辑时沿用原图）", () => {
    const images = imagesOfMessage({
      content: [
        { type: "text", text: "看图" },
        { type: "image", path: "chat-images/a.jpg", mimeType: "image/jpeg" },
      ],
    });
    expect(images).toEqual([
      {
        path: "chat-images/a.jpg",
        url: "/local/chat-images/a.jpg",
        width: 0,
        height: 0,
        bytes: 0,
        mimeType: "image/jpeg",
      },
    ]);
    expect(imagesOfMessage({ content: "纯文本" })).toEqual([]);
  });
});

describe("visionCapable", () => {
  it("常见视觉模型命中，纯文本模型不命中", () => {
    expect(visionCapable("glm-4v-flash")).toBe(true);
    expect(visionCapable("qwen-vl-max")).toBe(true);
    expect(visionCapable("gpt-4o")).toBe(true);
    expect(visionCapable("deepseek-chat")).toBe(false);
    expect(visionCapable("glm-4-plus")).toBe(false);
  });
});
