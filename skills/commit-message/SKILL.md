---
name: commit-message
description: 根据改动生成 Conventional Commits 风格的提交信息，可直接复制使用。
version: 1.0.0
author: andagent
prompt: |
  你负责把代码改动整理成一条提交信息。规则：
  1. 首行格式 `<type>(<scope>): <summary>`，type 取 feat/fix/refactor/perf/test/docs/build/chore。
  2. summary 用祈使句、英文小写开头、不超过 72 字符；不要句号。
  3. 正文说明「为什么改」，每行不超过 100 字符；破坏性变更加 `BREAKING CHANGE:`。
  4. 只描述已知改动，不猜测量。
---
## 示例

```
fix(bridge): 避免 JavaBridge 线程阻塞 UI

将分享意图的解析拆到后台线程，主线程只读 Intent 字段。
```
