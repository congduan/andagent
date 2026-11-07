import { execSync } from "node:child_process";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// git commit 号编译期注入（关于页展示）；非 git 环境回退 unknown。
function gitShortHead(): string {
  // ANDAGENT_GIT_COMMIT 由 Gradle buildWeb 注入（打包 HEAD 与发布提交一致）；
  // 手动 `npm run build` 时回退本地 rev-parse；非 git 环境回退 unknown。
  const injected = process.env.ANDAGENT_GIT_COMMIT;
  if (injected && injected !== "unknown") return injected;
  try {
    return execSync("git rev-parse --short HEAD", { cwd: __dirname, stdio: ["ignore", "pipe", "ignore"] })
      .toString()
      .trim();
  } catch {
    return "unknown";
  }
}

export default defineConfig({
  base: "./",
  plugins: [react()],
  define: {
    __BUILD_TIME__: JSON.stringify(new Date().toISOString()),
    __GIT_COMMIT__: JSON.stringify(gitShortHead()),
  },
  build: {
    outDir: "../app/src/main/assets/web",
    emptyOutDir: true,
    target: "es2020",
    chunkSizeWarningLimit: 8000,
  },
});
