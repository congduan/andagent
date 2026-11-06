// R93 收口：把 pi bundle 加载从 agent.ts 抽出，便于测试 vi.mock("./pi") 注入 fake 流。
import type { PiModule } from "./piTypes";

let piPromise: Promise<PiModule> | null = null;

/** Load the esbuild-produced pi bundle from the app's asset root. */
export function loadPi(): Promise<PiModule> {
  if (!piPromise) {
    piPromise = (async () => {
      const url = new URL("pi-bundle.js", document.baseURI).href;
      const mod = (await import(/* @vite-ignore */ url)) as PiModule;
      mod.registerBuiltInApiProviders();
      return mod;
    })();
  }
  return piPromise;
}

/** 测试专用：重置已缓存的 bundle promise。生产代码不调用。 */
export function resetPiForTest(): void {
  piPromise = null;
}
