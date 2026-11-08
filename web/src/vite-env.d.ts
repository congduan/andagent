declare const __BUILD_TIME__: string;
declare const __GIT_COMMIT__: string;
declare module "*?raw" { const content: string; export default content; }

/** Vite 注入的编译期常量（只声明本项目用到的字段）。 */
interface ImportMetaEnv {
  readonly DEV: boolean;
  readonly PROD: boolean;
  readonly MODE: string;
  /** 设为 "0" 关闭 PC 预览的假 AndroidBridge（web/src/devMockBridge.ts）。 */
  readonly VITE_ANDAGENT_MOCK_BRIDGE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
