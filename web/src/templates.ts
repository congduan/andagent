/** 首页空会话的可点击对话模板。
 *  文案与 agent.ts / extraTools.ts 里真实存在的工具对齐
 *  （storage_scan / screen_capture / battery_detail / thermal_status /
 *   usage_stats / torch / album_stats / image_collage / image_edit /
 *   zip_create / qr_generate / alarm_set / db_create / widget_generate /
 *   cron_create / cron_list / shortcut_create / countdown / docx_create /
 *   xlsx_create / pptx_create / web_read / clipboard_write / web_search /
 *   script_save / mermaid / chart / devtools_errors / http_request /
 *   memory_add / calendar_reminder_check / calendar_add / wifi_connect /
 *   lan_scan / data_usage / bt_scan / hotspot / airpods_status /
 *   heart_rate / brightness_set / audio_set_volume / wallpaper_set /
 *   vibrate / notif_events_read / screen_record / sensor_list /
 *   open_settings / camera_capture / camera_scan / media_library /
 *   file_download / file_hash / tts_to_file / live_start /
 *   speech_recognize / mic_record / location_get / sms_read /
 *   cell_info / nr_bandwidth / vpn_status / usb_list / contacts_search /
 *   phone_call / sms_send / call_log_read / email_draft /
 *   steps_today / health_sleep / health_weekly_report）。
 *  首页每次进入只随机展示其中一部分，见 pickTemplateGroups。 */

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
      {
        icon: "thermometer",
        title: { zh: "手机有点烫，帮我看看温度", en: "Check why my phone is hot" },
        prompt: { zh: "手机有点发烫，帮我看看当前温度和降频情况，再告诉我可能是什么在耗电。", en: "My phone is getting hot. Check the current temperature and throttling status, then tell me what is likely draining power." },
      },
      {
        icon: "activity",
        title: { zh: "这周我在哪些 App 上花时间最多", en: "Which apps took my time this week" },
        prompt: { zh: "统计一下我这周的使用时长，排出前 5 个最耗时间的 App，并给我一条建议。", en: "Check my usage this week, rank the top five apps by time spent, and give me one suggestion." },
      },
      {
        icon: "zap",
        title: { zh: "打开手电筒", en: "Turn on the flashlight" },
        prompt: { zh: "帮我打开手电筒。", en: "Turn on the flashlight for me." },
      },
      {
        icon: "video",
        title: { zh: "录一段屏幕操作视频", en: "Record my screen" },
        prompt: { zh: "帮我录 30 秒的屏幕视频，录完告诉我保存路径。", en: "Record a 30-second screen video and tell me where it was saved." },
      },
      {
        icon: "activity",
        title: { zh: "手机里有哪些传感器", en: "List my phone's sensors" },
        prompt: { zh: "列出这台手机支持的所有传感器，简单说明每个能用来做什么。", en: "List every sensor this phone supports and briefly explain what each one can do." },
      },
      {
        icon: "settings",
        title: { zh: "帮我打开 Wi-Fi 设置页", en: "Open Wi-Fi settings" },
        prompt: { zh: "帮我直接打开系统的 Wi-Fi 设置页。", en: "Open the system Wi-Fi settings page for me." },
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
      {
        icon: "palette",
        title: { zh: "把这张照片裁成 16:9 并提亮", en: "Crop a photo to 16:9 and brighten it" },
        prompt: { zh: "把相册里最新的一张照片裁成 16:9 再提亮一点，保存一份新的。", en: "Crop the newest photo in my library to 16:9, brighten it a little, and save a new copy." },
      },
      {
        icon: "package",
        title: { zh: "把下载目录打包成 zip", en: "Zip my Downloads folder" },
        prompt: { zh: "把下载目录里的文件打包成一个 zip，告诉我文件大小和保存路径。", en: "Zip the files in my Downloads folder, then tell me the archive size and path." },
      },
      {
        icon: "link",
        title: { zh: "生成一张二维码", en: "Generate a QR code" },
        prompt: { zh: "帮我生成一张内容为 https://github.com/andagent 的二维码图片并保存。", en: "Generate a QR code image for https://github.com/andagent and save it." },
      },
      {
        icon: "scan-text",
        title: { zh: "扫一下这个二维码", en: "Scan a QR code" },
        prompt: { zh: "打开相机扫一下这个二维码或条码，告诉我里面是什么内容。", en: "Use the camera to scan this QR code or barcode and tell me what is inside." },
      },
      {
        icon: "camera",
        title: { zh: "用超广角拍一张", en: "Shoot with the ultrawide lens" },
        prompt: { zh: "用后置超广角镜头拍一张照片，拍完告诉我效果怎么样。", en: "Take a photo with the rear ultrawide lens and tell me how it turned out." },
      },
      {
        icon: "folder",
        title: { zh: "看看本地有哪些视频和音频", en: "List local videos and audio" },
        prompt: { zh: "统计一下手机本地存储里的视频和音频文件，各列出最大的 5 个。", en: "Count the video and audio files on my phone and list the five largest of each." },
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
      {
        icon: "repeat",
        title: { zh: "每天 9 点自动整理昨天的通知", en: "Summarize yesterday’s notifications daily" },
        prompt: { zh: "加一个定时任务：每天早上 9 点把昨天的通知按 App 归类，挑出需要我处理的事项。", en: "Add a scheduled task that runs at 9 a.m. each day, groups yesterday’s notifications by app, and lists what needs my attention." },
      },
      {
        icon: "sparkles",
        title: { zh: "桌面加个一键记账图标", en: "Add a one-tap expense shortcut" },
        prompt: { zh: "在桌面加一个「一键记账」的快捷图标，点一下就自动开始帮我记一笔账。", en: "Add a “quick expense” shortcut to my home screen that starts recording an expense with one tap." },
      },
      {
        icon: "timer",
        title: { zh: "设一个 25 分钟专注计时", en: "Start a 25-minute focus timer" },
        prompt: { zh: "帮我把计时器设成 25 分钟，结束的时候提示我休息一下。", en: "Set a 25-minute timer and remind me to take a break when it ends." },
      },
      {
        icon: "repeat",
        title: { zh: "看看我有哪些定时任务", en: "Review my scheduled tasks" },
        prompt: { zh: "列一下现在所有的定时任务，告诉我哪些在运行、哪些停了，帮我清理没用的。", en: "List all my scheduled tasks, tell me which are active, and suggest ones worth removing." },
      },
      {
        icon: "calendar",
        title: { zh: "在日历里加一条例会", en: "Add a meeting to my calendar" },
        prompt: { zh: "帮我在下周一下午 3 点的日历里加一条「项目例会」，提前 15 分钟提醒我。", en: "Add a \"project sync\" to my calendar next Monday at 3 p.m. and remind me 15 minutes before." },
      },
      {
        icon: "music",
        title: { zh: "把一段话转成语音", en: "Turn text into speech" },
        prompt: { zh: "把「今天下午三点记得开会」合成一段语音文件，我想直接播放。", en: "Turn \"Remember the meeting at 3 p.m. today\" into an audio file I can play." },
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
      {
        icon: "bar-chart",
        title: { zh: "把本月开销做成 Excel", en: "Turn this month’s expenses into Excel" },
        prompt: { zh: "从记账数据库里查出这个月的开销，按类别汇总后生成一份 Excel 表格。", en: "Query this month’s expenses from my expense database, group them by category, and create an Excel sheet." },
      },
      {
        icon: "film",
        title: { zh: "做一份 5 页的产品介绍 PPT", en: "Create a five-slide product deck" },
        prompt: { zh: "帮我做一份 5 页的产品介绍 PPT，包含背景、痛点、方案、亮点和下一步，文案你先起草。", en: "Create a five-slide product deck covering context, pain points, solution, highlights, and next steps, drafting the copy first." },
      },
      {
        icon: "compass",
        title: { zh: "读一下这个网页并总结", en: "Read and summarize a web page" },
        prompt: { zh: "读一下 https://developer.android.com/about/versions 这个页面，把关键变化总结成几条。", en: "Read https://developer.android.com/about/versions and summarize the key changes." },
      },
      {
        icon: "mic",
        title: { zh: "录一段语音转成文字", en: "Record and transcribe a voice note" },
        prompt: { zh: "帮我录 30 秒语音并识别成文字，再整理成几条备忘。", en: "Record 30 seconds of speech, transcribe it, and turn it into a few short notes." },
      },
      {
        icon: "download",
        title: { zh: "下载这个链接的文件", en: "Download a file from a link" },
        prompt: { zh: "帮我把 https://github.com/andagent/andagent/releases/latest 这个页面的 APK 下载到手机，告诉我保存路径。", en: "Download the APK from https://github.com/andagent/andagent/releases/latest to my phone and tell me the path." },
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
      {
        icon: "file-code",
        title: { zh: "脚本统计目录里的文件类型", en: "Script a file-type breakdown" },
        prompt: { zh: "写一个脚本统计我笔记目录里各类文件的数量和总大小，输出成表格。", en: "Write a script that counts files by type in my notes directory with total sizes and prints a table." },
      },
      {
        icon: "monitor",
        title: { zh: "用 DevTools 看看网页报了什么错", en: "Check page errors with DevTools" },
        prompt: { zh: "用 DevTools 连上当前网页，看看控制台有哪些报错，按严重程度排序告诉我。", en: "Connect DevTools to the current page, list the console errors, and rank them by severity." },
      },
      {
        icon: "bug",
        title: { zh: "调一个接口看看返回结构", en: "Inspect an API response" },
        prompt: { zh: "帮我请求 https://api.github.com/repos/torvalds/linux ，看看返回的 JSON 结构，把关键字段说明一下。", en: "Request https://api.github.com/repos/torvalds/linux, inspect the JSON structure, and explain the key fields." },
      },
      {
        icon: "check",
        title: { zh: "算一下下载文件的校验和", en: "Verify a downloaded file's checksum" },
        prompt: { zh: "算一下我下载目录里最新那个文件的 SHA256，给我完整校验和。", en: "Compute the SHA256 of the newest file in my Downloads folder and give me the full checksum." },
      },
      {
        icon: "refresh",
        title: { zh: "加一个实时刷新电量的循环", en: "Run a live battery feed" },
        prompt: { zh: "建一个实时循环，每秒读一次电量并推送到对话里，跑 10 秒后自动停。", en: "Create a live loop that reads the battery level every second, pushes it to the chat, and stops after 10 seconds." },
      },
    ],
  },
  {
    label: { zh: "信息与阅读", en: "Search and reading" },
    templates: [
      {
        icon: "search",
        title: { zh: "今天有什么值得看的科技新闻", en: "Today’s tech news worth reading" },
        prompt: { zh: "搜一下今天值得看的科技新闻，挑 3 条最有信息量的总结给我。", en: "Search today’s tech news and summarize the three most informative stories." },
      },
      {
        icon: "brain",
        title: { zh: "记住我的常用收货地址", en: "Remember my shipping address" },
        prompt: { zh: "记住我的常用收货地址和收件人信息，以后我说「寄到家里」就用这个。", en: "Remember my usual shipping address and recipient so “ship it home” works later." },
      },
      {
        icon: "clipboard",
        title: { zh: "总结我剪贴板里的内容", en: "Summarize what’s on my clipboard" },
        prompt: { zh: "读一下我剪贴板里的内容，帮我总结成三条要点。", en: "Read what is on my clipboard and summarize it into three bullet points." },
      },
      {
        icon: "calendar",
        title: { zh: "看看今天的日程安排", en: "Review today’s schedule" },
        prompt: { zh: "看看我今天有哪些日程和提醒，按时间顺序列出来，告诉我第一个要准备什么。", en: "List today’s events and reminders in chronological order and tell me what to prepare first." },
      },
      {
        icon: "globe",
        title: { zh: "查一下某个词是什么意思", en: "Look up a term" },
        prompt: { zh: "帮我查一下「向量数据库」是什么意思，用一段话讲清楚，再给一个类比。", en: "Look up what a “vector database” is, explain it in a paragraph, and give an analogy." },
      },
      {
        icon: "map-pin",
        title: { zh: "我现在在哪里，附近有什么", en: "Where am I and what's nearby" },
        prompt: { zh: "查一下我现在的位置，告诉我大概在什么地方、附近有什么值得去的。", en: "Check my current location and tell me roughly where I am and what is worth visiting nearby." },
      },
      {
        icon: "message",
        title: { zh: "翻一下最近的短信", en: "Review recent SMS" },
        prompt: { zh: "读一下最近两天的短信，按发件人归类，把需要我处理的挑出来。", en: "Read SMS from the last two days, group them by sender, and flag the ones that need my attention." },
      },
    ],
  },
  {
    label: { zh: "网络与连接", en: "Network and connectivity" },
    templates: [
      {
        icon: "wifi",
        title: { zh: "我保存过哪些 Wi-Fi", en: "List my saved Wi-Fi networks" },
        prompt: { zh: "列一下我保存过的 Wi-Fi 网络，把很久没连过的挑出来给我看看。", en: "List my saved Wi-Fi networks and point out the ones I have not connected to in a while." },
      },
      {
        icon: "antenna",
        title: { zh: "看看局域网里有哪些设备", en: "Scan my local network" },
        prompt: { zh: "扫一下当前局域网，看看有哪些设备在线，标出可能的类型。", en: "Scan the current local network, list the online devices, and guess what type each one is." },
      },
      {
        icon: "activity",
        title: { zh: "这个月流量用了多少", en: "How much data did I use this month" },
        prompt: { zh: "查一下这个月的流量用量，按 App 排一下前几名。", en: "Check this month’s mobile data usage and rank the top apps." },
      },
      {
        icon: "bluetooth",
        title: { zh: "扫描附近的蓝牙设备", en: "Scan nearby Bluetooth devices" },
        prompt: { zh: "扫描一下附近的蓝牙设备，把信号最强的前几个列出来，告诉我可能是些什么设备。", en: "Scan nearby Bluetooth devices, list the strongest signals, and guess what they might be." },
      },
      {
        icon: "plug",
        title: { zh: "打开热点共享网络", en: "Turn on the hotspot" },
        prompt: { zh: "帮我打开热点，并告诉我当前的名称和连接情况。", en: "Turn on my hotspot and tell me the current name and connection status." },
      },
      {
        icon: "headphones",
        title: { zh: "查一下耳机电量", en: "Check my earbuds battery" },
        prompt: { zh: "查一下我蓝牙耳机的电量，左右耳和充电盒都看一下。", en: "Check my earbuds’ battery for the left, right, and charging case." },
      },
      {
        icon: "antenna",
        title: { zh: "诊断一下手机信号", en: "Diagnose my cellular signal" },
        prompt: { zh: "诊断一下当前的蜂窝信号：网络类型、频段、信号强度，评级好不好，5G 是 SA 还是 NSA。", en: "Diagnose my cellular signal: network type, band, signal strength, quality rating, and whether 5G is SA or NSA." },
      },
      {
        icon: "lock",
        title: { zh: "看看 VPN 现在的状态", en: "Check my VPN status" },
        prompt: { zh: "看一下 VPN 现在的状态：跑了多久、分流规则、DNS 拦截情况。", en: "Check my VPN status: how long it has been running, split rules, and DNS filtering stats." },
      },
      {
        icon: "plug",
        title: { zh: "看看 USB 口上插了什么", en: "Identify what is plugged into USB" },
        prompt: { zh: "枚举一下 USB 口上的设备，告诉我插的是什么类型的设备、权限给没给。", en: "Enumerate the devices on my USB port and tell me what type each one is and whether I granted access." },
      },
    ],
  },
  {
    label: { zh: "生活与健康", en: "Life and health" },
    templates: [
      {
        icon: "heart",
        title: { zh: "测一下我现在的心率", en: "Measure my heart rate" },
        prompt: { zh: "帮我测一下现在的心率，并告诉我这个数值算不算正常范围。", en: "Measure my heart rate and tell me whether the number is in a normal range." },
      },
      {
        icon: "sun",
        title: { zh: "把屏幕亮度调低一点", en: "Lower my screen brightness" },
        prompt: { zh: "把屏幕亮度调到 30%，晚上看着舒服一点。", en: "Set screen brightness to 30% so it is easier on the eyes at night." },
      },
      {
        icon: "volume",
        title: { zh: "把媒体音量调到一半", en: "Set media volume to half" },
        prompt: { zh: "把媒体音量调到一半，再告诉我现在的音量百分比。", en: "Set media volume to half and tell me the current percentage." },
      },
      {
        icon: "image",
        title: { zh: "换一张今日壁纸", en: "Change today’s wallpaper" },
        prompt: { zh: "从相册里挑一张风景照片设为壁纸。", en: "Pick a landscape photo from my library and set it as wallpaper." },
      },
      {
        icon: "vibrate",
        title: { zh: "让手机震一下，我找不到它了", en: "Make my phone vibrate" },
        prompt: { zh: "让手机震动一下，我一时找不到它了。", en: "Make my phone vibrate so I can find it." },
      },
      {
        icon: "message",
        title: { zh: "看看最近都收到了哪些通知", en: "Review recent notifications" },
        prompt: { zh: "看看最近的系统通知，按 App 归类，挑出重要的几条告诉我。", en: "Review recent notifications, group them by app, and point out the important ones." },
      },
      {
        icon: "activity",
        title: { zh: "今天走了多少步", en: "How many steps today" },
        prompt: { zh: "查一下我今天走了多少步，和这周的日均对比一下。", en: "Check today's step count and compare it with this week's daily average." },
      },
      {
        icon: "moon",
        title: { zh: "昨晚睡得怎么样", en: "How did I sleep last night" },
        prompt: { zh: "看看我昨晚的睡眠数据，睡了多久、深睡比例如何，给一句评价。", en: "Check last night's sleep data — duration and deep-sleep share — and give me a one-line assessment." },
      },
      {
        icon: "heart",
        title: { zh: "生成一份健康周报", en: "Create a weekly health report" },
        prompt: { zh: "把这周的心率、步数和睡眠数据汇总成一份健康周报，指出值得注意的变化。", en: "Summarize this week's heart rate, steps, and sleep into a health report and flag notable changes." },
      },
    ],
  },
  {
    label: { zh: "通信与联系", en: "Calls and contacts" },
    templates: [
      {
        icon: "search",
        title: { zh: "查一下联系人的号码", en: "Look up a contact" },
        prompt: { zh: "帮我在通讯录里查一下「张伟」的手机号和邮箱。", en: "Look up Zhang Wei's phone number and email in my contacts." },
      },
      {
        icon: "phone",
        title: { zh: "给张伟打个电话", en: "Call Zhang Wei" },
        prompt: { zh: "帮我在通讯录里找到张伟的号码并拨打。", en: "Find Zhang Wei in my contacts and place a call." },
      },
      {
        icon: "message",
        title: { zh: "发条短信说快到了", en: "Text that I'm almost there" },
        prompt: { zh: "给张伟发条短信：「我十分钟后到，稍等我一下。」", en: "Text Zhang Wei: \"I'll be there in ten minutes, wait for me.\"" },
      },
      {
        icon: "clock",
        title: { zh: "看看今天的未接来电", en: "Review today's missed calls" },
        prompt: { zh: "查一下今天的通话记录，把未接来电列出来，标注哪个号码值得回。", en: "Check today's call log, list the missed calls, and note which ones are worth calling back." },
      },
      {
        icon: "file-text",
        title: { zh: "起草一封报价邮件", en: "Draft a quote email" },
        prompt: { zh: "帮我起草一封给客户的报价邮件，产品是定制键盘，报价 1200 元，写完用系统邮件打开草稿。", en: "Draft a quote email to a client for a custom keyboard at 1,200 yuan, then open it in the mail app." },
      },
    ],
  },
];

/** Fisher–Yates 洗牌，返回新数组，不改动入参。 */
function shuffled<T>(items: readonly T[]): T[] {
  const out = items.slice();
  for (let i = out.length - 1; i > 0; i -= 1) {
    const j = Math.floor(Math.random() * (i + 1));
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out;
}

export interface TemplatePickOptions {
  /** 展示几组，默认 3 组。 */
  groupCount?: number;
  /** 每组展示几个，默认 3 个。 */
  perGroup?: number;
}

/** 首页每次进入时随机抽一部分模板：分组随机、组序随机、组内卡片随机。 */
export function pickTemplateGroups({ groupCount = 3, perGroup = 3 }: TemplatePickOptions = {}): TemplateGroup[] {
  const groups = Math.max(1, groupCount);
  const size = Math.max(1, perGroup);
  return shuffled(TEMPLATE_GROUPS)
    .slice(0, groups)
    .map((group) => ({ label: group.label, templates: shuffled(group.templates).slice(0, size) }))
    .filter((group) => group.templates.length > 0);
}
