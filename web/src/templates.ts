/** 首页空会话的可点击对话模板。
 *  文案与 agent.ts / extraTools.ts 里真实存在的工具对齐
 *  （storage_scan / screen_capture / battery_detail / album_stats /
 *   image_collage / alarm_set / db_create / widget_generate /
 *   docx_create / clipboard_write / web_search / script_save /
 *   mermaid / chart / js_eval+andagent.tool）。 */

import type { IconName } from "./icons";

export interface LocalizedText { zh: string; en: string }

export interface ConversationTemplate {
  /** 卡片上的图标（见 icons.tsx，SVG 图标名） */
  icon: IconName;
  /** 卡片标题（两行截断显示） */
  title: LocalizedText;
  /** 点击后作为用户消息直接发送的完整 prompt */
  prompt: LocalizedText;
}

export interface TemplateGroup {
  label: LocalizedText;
  templates: ConversationTemplate[];
}

export const TEMPLATE_GROUPS: TemplateGroup[] = [
  {
    label: { zh: "设备与系统", en: "Device and system" },
    templates: [
      {
        icon: "folder",
        title: { zh: "看看存储都被什么占满了", en: "Find what is using storage" },
        prompt: { zh: "看看手机存储都被什么占满了，给我一份占用最大的目录和文件清单，再给几条清理建议。", en: "Find what is filling my phone storage. List the largest directories and files, then suggest a few ways to free space." },
      },
      {
        icon: "camera",
        title: { zh: "截个屏给我看看", en: "Take a screenshot" },
        prompt: { zh: "帮我截一张当前屏幕的图，我想看看现在屏幕上是什么。", en: "Take a screenshot of the current screen so I can see what is on it." },
      },
      {
        icon: "battery",
        title: { zh: "现在电量、网络、内存状态如何", en: "Check battery, network, and memory" },
        prompt: { zh: "查一下手机现在的电量、Wi-Fi 网络和内存状态，用一小段总结告诉我。", en: "Check the current battery, Wi-Fi, and memory status of my phone and summarize them briefly." },
      },
    ],
  },
  {
    label: { zh: "相册与文件", en: "Photos and files" },
    templates: [
      {
        icon: "image",
        title: { zh: "相册里截图占多少空间？", en: "How much space do screenshots use?" },
        prompt: { zh: "统计一下相册里截图有多少张、总共占多少空间，和照片对比一下。", en: "Count screenshots in my photo library and their total size, then compare them with photos." },
      },
      {
        icon: "puzzle",
        title: { zh: "把最近的截图拼成九宫格", en: "Make a collage of recent screenshots" },
        prompt: { zh: "把相册里最近的 9 张截图拼成一张九宫格图片保存。", en: "Combine the nine most recent screenshots in my photo library into a 3×3 collage and save it." },
      },
      {
        icon: "file-text",
        title: { zh: "把下载的 PDF 摘要成要点", en: "Summarize a downloaded PDF" },
        prompt: { zh: "在下载目录里找一下最近下载的 PDF，把内容摘要成几条要点。", en: "Find the most recently downloaded PDF in Downloads and summarize its key points." },
      },
    ],
  },
  {
    label: { zh: "自动化与效率", en: "Automation and productivity" },
    templates: [
      {
        icon: "alarm",
        title: { zh: "明早 8 点提醒我开会", en: "Remind me of a meeting tomorrow at 8" },
        prompt: { zh: "明天早上 8 点提醒我开会。", en: "Remind me about my meeting at 8 a.m. tomorrow." },
      },
      {
        icon: "notebook",
        title: { zh: "建个记账数据库记一笔", en: "Create an expense database" },
        prompt: { zh: "建一个记账数据库，先记上今天早饭花了 15 元，以后可以继续用。", en: "Create an expense database and record that I spent 15 yuan on breakfast today so I can keep using it." },
      },
      {
        icon: "smartphone",
        title: { zh: "写个每天早上看天气的小组件", en: "Make a morning weather widget" },
        prompt: { zh: "帮我写一个桌面小组件，每天早上显示今天的天气。", en: "Create a home screen widget showing today’s weather each morning." },
      },
    ],
  },
  {
    label: { zh: "生产力", en: "Productivity" },
    templates: [
      {
        icon: "pencil",
        title: { zh: "生成一份周报 Word 文档", en: "Create a weekly Word report" },
        prompt: { zh: "帮我生成一份本周工作周报的 Word 文档，结构包含本周完成、数据小结和下周计划，内容你先拟一版再生成。", en: "Draft and create a Word document for this week’s work report, with completed work, a data summary, and next week’s plan." },
      },
      {
        icon: "globe",
        title: { zh: "翻译一句话并存到剪贴板", en: "Translate and copy a sentence" },
        prompt: { zh: "把这句话翻译成英文并写入剪贴板：「请把这份文件尽快发给我，谢谢。」", en: "Translate this sentence into English and copy it to the clipboard: “Please send me this file as soon as possible. Thank you.”" },
      },
      {
        icon: "search",
        title: { zh: "搜一下 Android 16 的新特性", en: "Search for Android 16 features" },
        prompt: { zh: "帮我搜一下 Android 16 的新特性，挑重点总结成几条。", en: "Search for new Android 16 features and summarize the main points." },
      },
    ],
  },
  {
    label: { zh: "开发与脚本", en: "Development and scripts" },
    templates: [
      {
        icon: "pencil",
        title: { zh: "写个脚本批量重命名笔记文件", en: "Write a note renaming script" },
        prompt: { zh: "写一个 JS 脚本，批量把我笔记目录里的文件重命名成「日期-标题」格式，先列出预览再执行。", en: "Write a JavaScript script to rename files in my notes directory to a date-title format. Preview the changes before running it." },
      },
      {
        icon: "ruler",
        title: { zh: "用 mermaid 画今天的日程流程", en: "Diagram today’s schedule with Mermaid" },
        prompt: { zh: "读一下我今天的日程安排，用 mermaid 流程图画出来。", en: "Read my schedule for today and draw it as a Mermaid flowchart." },
      },
      {
        icon: "bar-chart",
        title: { zh: "查电池数据画成图表", en: "Chart battery data" },
        prompt: { zh: "用 js_eval 里的 andagent.tool 查一下电池状态，把关键数据画成 chart 图表。", en: "Use andagent.tool in js_eval to check battery status and plot the key data in a chart." },
      },
    ],
  },
];
