import type {
  AssistantMessage,
  AssistantMessageEvent,
  Message as PiMessage,
  Model,
  Tool as PiTool,
  ToolCall,
  ToolResultMessage,
  UserMessage,
} from "@mariozechner/pi-ai";
import type { ThreadMessageLike } from "@assistant-ui/react";
import { cancelAgentWork, execTool, STOPPED_RESULT, type Settings } from "./bridge";
import { appConfirm } from "./ConfirmDialog";
import { PERMISSION_GUIDE, PERMISSION_GUIDE_EN } from "./permissionGuide";
import { currentLanguage } from "./i18n";
import { isModelConfigured, visionCapable } from "./modelConfig";
import {
  chatImageUrl,
  hydrateChatImages,
  imagesOfMessage,
  MAX_CHAT_IMAGES,
  type ChatImage,
} from "./chatImages";
import { toModelMessages } from "./modelMessages";
import { SessionStore, type SessionMeta } from "./sessions";
import { EXTRA_TOOL_DEFS, IMAGE_EDIT_OPS } from "./extraTools";
import { extensionTools, skillPrompt, memoryPrompt } from "./bridge";
import apiTools from "../../app/src/main/assets/api_tools.json";
import { addUsage, emptyUsage, sumMessageUsage, type UsageSum } from "./usage";
import { loadPi } from "./pi";
import type { PiModule } from "./piTypes";

const MAX_TOOL_ROUNDS = 200;

export type { PiModule };

const TOOL_DEFS = [
  {
    name: "get_time",
    description:
      "获取当前日期、时间、时区与星期。用户问现在几点、今天几号、今天星期几时使用。",
    parameters: {
      type: "object",
      properties: {
        timezone: {
          type: "string",
          description: "IANA 时区，例如 Asia/Shanghai。省略则使用设备本地时区。",
        },
      },
      required: [],
    },
  },
  {
    name: "device_info",
    description: "获取设备信息：型号、Android 版本、存储空间、内存、电量。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "storage_scan",
    description:
      "分析存储占用：剩余/总空间、下载/影片/音乐/文档目录下最大的文件、应用缓存大小。",
    parameters: {
      type: "object",
      properties: {
        top_n: { type: "integer", description: "每个目录列出最大的 N 个文件，默认 10" },
        path: { type: "string", description: "可选：只分析指定目录" },
      },
      required: [],
    },
  },
  {
    name: "album_stats",
    description: "相册快速统计：截图数量与体积、按年份的照片数量与体积。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "calendar_list",
    description: "列出设备上的日历账户（id、名称、所属账户、是否可写）。写入事件前可先用它选择 calendar_id。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "calendar_events",
    description:
      "查询日历事件：按时间窗 start/end 过滤，返回标题、开始/结束时间、地点、所属日历。最多 100 条。",
    parameters: {
      type: "object",
      properties: {
        start: { type: "string", description: "时间窗开始，例如 2026-11-01 或 2026-11-01 08:00；默认 30 天前" },
        end: { type: "string", description: "时间窗结束，格式同上；默认 30 天后" },
        calendar_id: { type: "integer", description: "可选：只看某个日历 id" },
      },
      required: [],
    },
  },
  {
    name: "calendar_add",
    description:
      "创建日历事件（会修改设备数据）：title/begin/end 必填，location/description/remindMinutes 可选。",
    parameters: {
      type: "object",
      properties: {
        title: { type: "string", description: "事件标题" },
        begin: { type: "string", description: "开始时间，例如 2026-11-05 14:00" },
        end: { type: "string", description: "结束时间，格式同上" },
        location: { type: "string", description: "可选：地点" },
        description: { type: "string", description: "可选：备注" },
        remindMinutes: { type: "integer", description: "可选：提前多少分钟提醒" },
        calendar_id: { type: "integer", description: "可选：目标日历 id，默认第一个可写日历" },
      },
      required: ["title", "begin", "end"],
    },
  },
  {
    name: "calendar_delete",
    description: "按事件 id 删除日历事件（会修改设备数据）。先用 calendar_events 查询 id。",
    parameters: {
      type: "object",
      properties: {
        id: { type: "integer", description: "要删除的事件 id" },
      },
      required: ["id"],
    },
  },
  {
    name: "alarm_set",
    description: "唤起系统闹钟应用设置闹钟（小时/分钟/备注），由用户在系统界面确认。",
    parameters: {
      type: "object",
      properties: {
        hour: { type: "integer", description: "小时，0-23" },
        minutes: { type: "integer", description: "分钟，0-59" },
        message: { type: "string", description: "可选：闹钟备注" },
      },
      required: ["hour", "minutes"],
    },
  },
  {
    name: "timer_set",
    description: "唤起系统时钟应用设置倒计时（秒），由用户在系统界面确认。",
    parameters: {
      type: "object",
      properties: {
        seconds: { type: "integer", description: "倒计时秒数" },
        message: { type: "string", description: "可选：计时器备注" },
      },
      required: ["seconds"],
    },
  },
  {
    name: "battery_detail",
    description: "读取电池详情：电量百分比、充电状态、供电方式、健康度、温度、电压。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "wifi_info",
    description:
      "读取 Wi-Fi 信息：SSID、信号强度、IP、链路速度。定位权限被拒时会降级为网络类型。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  { name: "bt_scan", description: "扫描附近蓝牙设备，返回名称、MAC 和 RSSI；需蓝牙扫描与定位权限。", parameters: { type: "object", properties: { duration: { type: "integer", description: "秒数，默认 10，范围 1~30" } }, required: [] } },
  { name: "bt_paired_list", description: "列出已配对蓝牙设备的名称、MAC 与类型。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "bt_connect", description: "对已配对 BLE 设备尝试 GATT 连接；未配对设备发起系统配对。", parameters: { type: "object", properties: { mac: { type: "string", description: "设备 MAC" } }, required: ["mac"] } },
  { name: "wifi_connect", description: "打开系统 Wi-Fi 面板，用户手动选择 SSID。", parameters: { type: "object", properties: { ssid: { type: "string", description: "网络名称" } }, required: ["ssid"] } },
  { name: "termux_setup", description: "安装并校验 Termux 用户空间；失败时检查 Termux App 回退。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "shell_exec", description: "在 Termux 用户空间执行 shell 命令。", parameters: { type: "object", properties: { cmd: { type: "string", description: "命令" }, timeout_s: { type: "integer", description: "超时秒数，默认 60" } }, required: ["cmd"] } },
  { name: "pkg_install", description: "安装 Termux 包；白名单外须 confirm=true。", parameters: { type: "object", properties: { pkg: { type: "string", description: "包名" }, confirm: { type: "boolean", description: "确认白名单外安装" } }, required: ["pkg"] } },
  { name: "widget_generate", description: "根据 spec_description 编写 WidgetSpec v2 JSON。schemaVersion=2，name，background=#RRGGBB，padding=0..32，root 为 COLUMN/ROW，children 节点为 COLUMN/ROW/TEXT/SPACER/PROGRESS；TEXT 可用 binding=DATA + source + field。data.sources 为名称到数据源的对象；类型 STATIC(value 对象)、HTTP(https url)、SYSTEM(system=BATTERY/CLOCK/NETWORK/LOCALE/STORAGE/DEVICE_INFO/BATTERY_DETAIL/STEPS_TODAY)、SCRIPT(script 字符串，定义 refresh(ctx) 返回扁平对象；可调用 andagent.tool('steps_today','{}') 并 JSON.parse)。支持 dependsOn、ttlSeconds、fallback、sample。最多 40 节点、6 层、8 数据源。调用后据此输出完整 spec，再调用 widget_save_and_pin。", parameters: { type: "object", properties: { spec_description: { type: "string", description: "用户希望的小组件内容与样式" } }, required: ["spec_description"] } },
  { name: "shortcut_create", description: "请求在桌面添加一键任务图标；点击后打开 AndAgent 并自动发送预置 prompt。启动器会弹出确认框。", parameters: { type: "object", properties: { name: { type: "string", description: "桌面显示名" }, prompt: { type: "string", description: "点击图标后自动发送的指令" }, emoji: { type: "string", description: "可选：图标上的单个 emoji 字符" }, mode: { type: "string", description: "可选：pin（默认，请求固定到桌面，启动器弹确认框）或 dynamic（动态快捷方式，无需启动器确认，槽位有限会被挤掉）" } }, required: ["name", "prompt"] } },
  { name: "shortcut_list", description: "列出本应用请求创建的桌面一键任务图标及 ID。", parameters: { type: "object", properties: {} } },
  { name: "shortcut_delete", description: "停用指定桌面快捷方式并移除本地记录；Android 不允许应用直接移除已 pin 图标，用户需长按图标拖到卸载才能彻底移除。", parameters: { type: "object", properties: { id: { type: "string", description: "shortcut_list 返回的 ID" } }, required: ["id"] } },
  { name: "tile_set", description: "配置下拉快捷栏磁贴：设置显示名与点击后自动发送的 prompt（复用 andagent://send deeplink）。已有磁贴需用户先从快捷栏添加；配置后自动请求系统刷新。", parameters: { type: "object", properties: { label: { type: "string", description: "磁贴显示名，1-32 字符，无控制字符" }, prompt: { type: "string", description: "点击磁贴后自动发送的指令，1-2000 字符，无控制字符" } }, required: ["label", "prompt"] } },
  { name: "tile_get", description: "读取当前下拉快捷栏磁贴配置（显示名/prompt），未配置返回 configured=false。", parameters: { type: "object", properties: {} } },
  { name: "tile_clear", description: "清除下拉快捷栏磁贴配置；磁贴回落为打开主界面。", parameters: { type: "object", properties: {} } },
  { name: "widget_save_and_pin", description: "验证并保存 WidgetSpec v2 JSON，然后请求添加到桌面。编辑时可在设置页改 JSON。", parameters: { type: "object", properties: { spec_json: { type: "string", description: "完整 WidgetSpec v2 JSON 字符串" } }, required: ["spec_json"] } },
  { name: "docx_create", description: "创建 Word 文档到 Downloads/andagent/。content_spec_json 是 JSON 字符串：{\"title\":\"季度报告\",\"paragraphs\":[{\"type\":\"heading\",\"text\":\"概览\",\"level\":2},{\"type\":\"p\",\"text\":\"正文\",\"bold\":true,\"italic\":false,\"size\":12,\"color\":\"#336699\",\"align\":\"left\"},{\"type\":\"bullet\",\"text\":\"要点\"},{\"type\":\"numbered\",\"text\":\"第一项\"},{\"type\":\"table\",\"headers\":[\"项目\",\"值\"],\"rows\":[[\"A\",\"1\"]]},{\"type\":\"pageBreak\"}]}。", parameters: { type: "object", properties: { filename: { type: "string" }, content_spec_json: { type: "string" } }, required: ["filename", "content_spec_json"] } },
  { name: "pptx_create", description: "创建 16:9 PowerPoint 到 Downloads/andagent/。slides_json 是 JSON 数组字符串：[{\"title\":\"季度报告\",\"bullets\":[\"要点一\",\"要点二\"],\"notes\":\"说明\"}]。也支持 texts:[{x,y,width,height,text,size,bold}]，坐标为英寸。", parameters: { type: "object", properties: { filename: { type: "string" }, slides_json: { type: "string" } }, required: ["filename", "slides_json"] } },
  { name: "xlsx_create", description: "创建 Excel 到 Downloads/andagent/。sheets_json 是 JSON 数组字符串：[{\"name\":\"统计\",\"rows\":[[\"项目\",\"数量\"],[\"A\",12],[\"B\",\"=B2*2\"]],\"columnWidths\":{\"1\":20}}]。数字保留为数值，= 开头为公式。", parameters: { type: "object", properties: { filename: { type: "string" }, sheets_json: { type: "string" } }, required: ["filename", "sheets_json"] } },
  { name: "script_save", description: "保存 JS 脚本到本地脚本库；覆盖已有文件须 confirm=true。保存前自动检查语法。", parameters: { type: "object", properties: { name: { type: "string" }, code: { type: "string" }, description: { type: "string" }, confirm: { type: "boolean" } }, required: ["name", "code"] } },
  { name: "script_list", description: "列出本地脚本库里的脚本、大小、修改时间和描述。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "script_read", description: "读取本地 JS 脚本内容；修改前先读。", parameters: { type: "object", properties: { name: { type: "string" } }, required: ["name"] } },
  { name: "script_run", description: "从本地脚本库加载并执行 JS；args_json 对象注入全局 args。", parameters: { type: "object", properties: { name: { type: "string" }, args_json: { type: "string" }, timeout_s: { type: "integer" } }, required: ["name"] } },
  { name: "script_delete", description: "删除本地 JS 脚本；必须 confirm=true。", parameters: { type: "object", properties: { name: { type: "string" }, confirm: { type: "boolean" } }, required: ["name"] } },
  { name: "script_check", description: "只检查 JS 语法，不运行源码。", parameters: { type: "object", properties: { code: { type: "string" } }, required: ["code"] } },
  { name: "json_fix", description: "修复并验证常见不规范 JSON：注释、尾逗号、单引号、未引号键、智能引号、截断容器、Python 值。", parameters: { type: "object", properties: { text: { type: "string" } }, required: ["text"] } },
  { name: "js_eval", description: "在 QuickJS 中执行脚本。脚本可用 andagent.tool(name, argsJson) 同步调用设备工具，andagent.log(msg) 记录日志；andagent.docx(specJson, filename)、andagent.pptx(specJson, filename)、andagent.xlsx(specJson, filename) 生成文档；最后表达式作为 JSON 结果。", parameters: { type: "object", properties: { script: { type: "string", description: "JavaScript 脚本" }, timeout_s: { type: "integer", description: "超时秒数，默认 30，最大 30" } }, required: ["script"] } },
  { name: "nfc_status", description: "查询设备是否支持 NFC 及开关状态。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "nfc_read", description: "等待贴卡最多 60 秒，读取 UID、技术和 NDEF 记录。调用前提示用户贴卡。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "nfc_write_text", description: "覆盖 NFC 卡片的 NDEF 内容为文本；调用前说明写入内容并提示贴卡。", parameters: { type: "object", properties: { text: { type: "string", description: "写入文本" } }, required: ["text"] } },
  { name: "nfc_write_uri", description: "覆盖 NFC 卡片的 NDEF 内容为 URI；调用前说明写入 URI 并提示贴卡。", parameters: { type: "object", properties: { uri: { type: "string", description: "写入 URI" } }, required: ["uri"] } },
  { name: "nfc_iso_dep_info", description: "等待贴卡并读取 ISO-DEP 卡的 ATS/历史字节、最大传输长度与 compact TLV；用于判断卡是否走 APDU 通道。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "nfc_iso_dep_transceive", description: "等待贴卡并透传 ISO-DEP APDU（IsoDep.transceive）：apdu 为 hex，可多条；aid 可自动 SELECT；SW=61xx 自动 GET RESPONSE。可读公交卡余额、门禁卡、DESFire、电子护照。证件/支付类 AID 需 confirm_privacy=true + purpose（须先取得用户同意），写入/校验 PIN 需 confirm=true。", parameters: { type: "object", properties: { apdu: { type: "string", description: "命令 APDU hex，多条用换行/分号/逗号分隔" }, aid: { type: "string", description: "可选：先自动 SELECT 的 AID" }, timeout_ms: { type: "integer", description: "超时毫秒，默认 5000" }, auto_get_response: { type: "boolean", description: "SW=61xx 自动 GET RESPONSE，默认 true" }, confirm: { type: "boolean", description: "写入/校验 PIN 类指令必须 true" }, confirm_privacy: { type: "boolean", description: "证件/支付类 AID 必须 true" }, purpose: { type: "string", description: "用户授权的用途" } }, required: [] } },
  { name: "nfc_felica_info", description: "等待贴 FeliCa 卡（Suica/ICOCA 等）并读取 IDm/PMm/系统码。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "nfc_felica_transceive", description: "等待贴 FeliCa 卡并透传原始帧（NfcF.transceive）；写入/认证类命令需 confirm=true。", parameters: { type: "object", properties: { frame: { type: "string", description: "FeliCa 帧 hex（含长度字节）" }, timeout_ms: { type: "integer", description: "超时毫秒，默认 5000" }, confirm: { type: "boolean", description: "写入/认证类命令必须 true" } }, required: ["frame"] } },
  { name: "nfc_felica_read_blocks", description: "等待贴 FeliCa 卡，用 Read Without Encryption 读块并返回 16 字节 hex/ASCII。", parameters: { type: "object", properties: { service_codes: { type: "string", description: "服务码，2 字节 hex，如 008B" }, blocks: { type: "string", description: "块号或 serviceIndex:blockNo，如 0 或 0:0,0:1" }, timeout_ms: { type: "integer", description: "超时毫秒，默认 5000" }, block_list_hex: { type: "string", description: "可选：直接给 block list 元素 hex" } }, required: ["service_codes", "blocks"] } },
  { name: "web_embed", description: "在对话中展示内嵌浏览器：支持 HTTPS 网站，以及本地 HTML 文件（file:///、content:// 或 /sdcard/... 绝对路径）。本地 HTML 会在原生内嵌 WebView 打开，可用 devtools_console / devtools_errors 查加载期报错、devtools_eval 验证 DOM（需 DevTools 调试开启；见网页开发工作流）。", parameters: { type: "object", properties: { uri: { type: "string", description: "HTTPS 网站地址，或本地 HTML 的 file:///、content:// URI / 绝对路径" } }, required: ["uri"] } },
  { name: "screen_capture", description: "经 scrcpy-server 取一帧屏幕并返回 JPEG 缩略图。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "screen_record_h264", description: "用 MediaProjection 录制 H.264 MP4 到 Movies，可设置时长与码率。", parameters: { type: "object", properties: { duration_s: { type: "integer", description: "秒数，默认 30" }, bit_rate: { type: "integer", description: "码率 bps" } }, required: [] } },
  { name: "a11y_status", description: "查询无障碍服务状态：是否已启用/已连接、当前前台包名与窗口、可用能力；未启用时返回开启路径。只读。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "a11y_screen", description: "读取当前前台窗口的无障碍节点树（viewId/text/contentDescription/class/bounds/可点击性），默认最多 80 节点、单条文本 200 字符；密码框内容不输出。只读；使用前先把目标应用切到前台。", parameters: { type: "object", properties: { max_nodes: { type: "integer", description: "最多返回节点数，默认 80，范围 1-400" } }, required: [] } },
  { name: "a11y_click", description: "按 view_id/text/content_description 查找控件并点击（匹配到不可点击节点时自动向上找可点击父节点）；找不到时返回候选控件。", parameters: { type: "object", properties: { view_id: { type: "string", description: "资源 id，如 com.app:id/login，或只写 login / :id/login 后缀匹配" }, text: { type: "string", description: "控件文本" }, content_description: { type: "string", description: "控件内容描述" }, match: { type: "string", enum: ["contains", "exact"], description: "text/content_description 匹配方式，默认 contains" }, index: { type: "integer", description: "同一选择器匹配到多个控件时的序号，从 0 开始，默认 0" } }, required: [] } },
  { name: "a11y_long_click", description: "按 view_id/text/content_description 查找控件并长按（自动向上找可长按/可点击父节点）；找不到时返回候选控件。", parameters: { type: "object", properties: { view_id: { type: "string", description: "资源 id，完整或后缀" }, text: { type: "string", description: "控件文本" }, content_description: { type: "string", description: "控件内容描述" }, match: { type: "string", enum: ["contains", "exact"], description: "匹配方式，默认 contains" }, index: { type: "integer", description: "匹配序号，默认 0" } }, required: [] } },
  { name: "a11y_scroll", description: "滚动当前窗口的可滚动控件：direction=forward/backward，可用选择器指定列表容器；省略则滚动第一个可滚动节点。", parameters: { type: "object", properties: { direction: { type: "string", enum: ["forward", "backward"], description: "滚动方向，默认 forward" }, view_id: { type: "string", description: "可选：可滚动控件 id" }, text: { type: "string", description: "可选：可滚动控件文本" }, content_description: { type: "string", description: "可选：可滚动控件描述" }, match: { type: "string", enum: ["contains", "exact"], description: "匹配方式，默认 contains" } }, required: [] } },
  { name: "a11y_text", description: "向输入框写入文本（先 ACTION_FOCUS 再 ACTION_SET_TEXT）；可用 view_id/content_description 指定输入框，省略则写当前焦点输入框。text 可为空字符串以清空。", parameters: { type: "object", properties: { text: { type: "string", description: "要写入的文本，最长 10000 字符；空字符串表示清空" }, view_id: { type: "string", description: "可选：输入框资源 id" }, content_description: { type: "string", description: "可选：输入框内容描述" }, match: { type: "string", enum: ["contains", "exact"], description: "匹配方式，默认 contains" } }, required: ["text"] } },
  { name: "a11y_action", description: "执行全局无障碍动作：back（返回）/ home（桌面）/ recents（最近任务）。", parameters: { type: "object", properties: { action: { type: "string", enum: ["back", "home", "recents"], description: "全局动作" } }, required: ["action"] } },
  {
    name: "clipboard_write",
    description: "把文本写入系统剪贴板，方便用户粘贴到其他应用。",
    parameters: {
      type: "object",
      properties: {
        text: { type: "string", description: "要复制的文本" },
        label: { type: "string", description: "可选：剪贴板标签，默认 AndAgent" },
      },
      required: ["text"],
    },
  },
  {
    name: "clipboard_read",
    description: "读取系统剪贴板文本（Android 10+ 需要应用窗口有焦点，失败会给明确原因）。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "notify_send",
    description: "发送一条本地通知（标题 + 内容）。Android 13+ 会申请通知权限。",
    parameters: {
      type: "object",
      properties: {
        title: { type: "string", description: "通知标题" },
        text: { type: "string", description: "通知内容" },
      },
      required: ["title", "text"],
    },
  },
  {
    name: "app_list",
    description: "列出已安装应用（包名、应用名、版本、是否系统应用），按安装时间倒序，最多 200 个。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "screen_record",
    description:
      "开始录屏（MediaProjection，首次会弹出一次系统授权弹窗）。duration 秒，默认 30，最大 300；立即返回保存路径，可调用 screen_record_stop 提前停止。",
    parameters: {
      type: "object",
      properties: {
        duration: { type: "integer", description: "录制秒数，默认 30，最大 300" },
      },
      required: [],
    },
  },
  {
    name: "screen_record_stop",
    description: "停止正在进行的 screen_record 录屏，并让媒体库扫描已保存的视频。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "screen_record_list",
    description:
      "列出已录制的屏幕视频（/sdcard/Movies/andagent_*.mp4），返回文件名、路径、大小、时长与修改时间。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "file_download",
    description:
      "用 HTTP GET 下载文件到 Downloads/andagent/，支持大文件与重定向；返回保存路径与大小。",
    parameters: {
      type: "object",
      properties: {
        url: { type: "string", description: "下载地址（http/https）" },
        filename: { type: "string", description: "可选：保存文件名，默认从 URL 推断" },
      },
      required: ["url"],
    },
  },
  {
    name: "file_search",
    description:
      "按文件名搜索用户文件，支持关键词或 *.pdf 这类通配符；返回路径、大小、类型、修改时间，最多 50 条。",
    parameters: {
      type: "object",
      properties: {
        query: {
          type: "string",
          description: "文件名关键词或通配符，例如 报告、*.pdf、IMG_2026*",
        },
        dir: {
          type: "string",
          description: "可选：限定目录 downloads|documents|movies|music|pictures",
        },
      },
      required: ["query"],
    },
  },
  {
    name: "file_write",
    description:
      "写入文本文件，覆盖同名文件（不会生成 x (1).txt 副本）。dir 为 downloads（Download/andagent/）、documents（Documents/）或 app_private（应用私有目录）；name 可含子目录。也可用 path 给绝对路径。返回完整路径与字节数。",
    parameters: {
      type: "object",
      properties: {
        dir: { type: "string", description: "downloads | documents | app_private，默认 downloads" },
        name: { type: "string", description: "文件名或相对路径，可含子目录，例如 notes/2026.md" },
        path: { type: "string", description: "可选：绝对路径，优先于 dir+name" },
        content: { type: "string", description: "文件内容（UTF-8）" },
      },
      required: ["content"],
    },
  },
  {
    name: "file_read",
    description:
      "读取文本文件，按行窗口返回，每行带行号前缀（如 `    12\\t内容`）。可用 path（绝对路径或 content://），也可用 dir（downloads|documents|app_private）+ name。用 offset/limit 翻页，单次最多 2000 行或 256KB。传给 file_edit 的 old_string 时请去掉行号前缀。",
    parameters: {
      type: "object",
      properties: {
        path: { type: "string", description: "文件绝对路径或 content:// URI" },
        dir: { type: "string", description: "与 name 搭配：downloads | documents | app_private" },
        name: { type: "string", description: "文件相对路径（与 dir 搭配，可含子目录）" },
        offset: { type: "integer", description: "起始行号，从 1 开始，默认 1" },
        limit: { type: "integer", description: "最多返回行数，默认 2000，上限 2000" },
        numbered: { type: "boolean", description: "是否加行号前缀，默认 true" },
      },
      required: [],
    },
  },
  {
    name: "file_edit",
    description:
      "精确替换文件中的文本：把 old_string 换成 new_string。old_string 必须与文件内容完全一致且唯一（重复时报错并给出行号）；一次替换多处设 replace_all=true，删除内容时 new_string 传空字符串。修改已有文件请优先用它，而不是 file_write 整体重写。",
    parameters: {
      type: "object",
      properties: {
        path: { type: "string", description: "文件绝对路径或 content:// URI" },
        dir: { type: "string", description: "与 name 搭配：downloads | documents | app_private" },
        name: { type: "string", description: "文件相对路径（与 dir 搭配）" },
        old_string: { type: "string", description: "要替换的原文本，需与文件内容完全一致" },
        new_string: { type: "string", description: "替换后的文本；空字符串表示删除该片段" },
        replace_all: { type: "boolean", description: "为 true 时替换所有匹配，默认 false" },
      },
      required: ["old_string", "new_string"],
    },
  },
  {
    name: "file_list",
    description:
      "列出 Download/andagent/（默认）或指定公共目录下的文件，返回名称、大小、修改时间，最多 100 条。",
    parameters: {
      type: "object",
      properties: {
        dir: { type: "string", description: "downloads（默认）|documents|movies|music|pictures" },
        subdir: { type: "string", description: "可选：子目录名；downloads 默认 andagent，其他默认根目录" },
      },
      required: [],
    },
  },
  {
    name: "sensor_list",
    description: "枚举设备全部传感器：名称、类型、厂商、分辨率、最大量程、功耗、最小采样间隔。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "sensor_read",
    description:
      "读取指定传感器当前值（采样 300ms 取平均）。支持 accelerometer|gyroscope|magnetometer|light|pressure|proximity|humidity|ambient_temp；返回数值、单位与物理含义。",
    parameters: {
      type: "object",
      properties: {
        sensor: {
          type: "string",
          description:
            "accelerometer | gyroscope | magnetometer | light | pressure | proximity | humidity | ambient_temp",
        },
      },
      required: ["sensor"],
    },
  },
  {
    name: "steps_today",
    description: "读取今天的步数（Health Connect，需要用户授权步数读取权限）。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "steps_history",
    description: "读取最近 N 天每日步数（Health Connect），默认 7 天，最多 30 天。",
    parameters: {
      type: "object",
      properties: {
        days: { type: "integer", description: "天数，默认 7，范围 1–30" },
      },
      required: [],
    },
  },
  {
    name: "health_heart_rate",
    description: "读取最近 N 天心率（Health Connect）：每日最低/平均/最高与静息心率，默认 7 天，最多 30 天。",
    parameters: {
      type: "object",
      properties: {
        days: { type: "integer", description: "天数，默认 7，范围 1–30" },
      },
      required: [],
    },
  },
  {
    name: "health_sleep",
    description: "读取最近 N 天睡眠（Health Connect）：每次起止、时长与深睡/浅睡/REM/清醒分期，默认 7 天，最多 30 天。",
    parameters: {
      type: "object",
      properties: {
        days: { type: "integer", description: "天数，默认 7，范围 1–30" },
      },
      required: [],
    },
  },
  {
    name: "health_spo2",
    description: "读取最近 N 天血氧 SpO2（Health Connect）：每日最低/平均/最高百分比，默认 7 天，最多 30 天。",
    parameters: {
      type: "object",
      properties: {
        days: { type: "integer", description: "天数，默认 7，范围 1–30" },
      },
      required: [],
    },
  },
  {
    name: "health_weight",
    description: "读取最近 N 天体重（Health Connect）：逐条读数、最新值与区间变化，默认 30 天，最多 90 天。",
    parameters: {
      type: "object",
      properties: {
        days: { type: "integer", description: "天数，默认 30，范围 1–90" },
      },
      required: [],
    },
  },
  {
    name: "health_exercise",
    description: "读取最近 N 天运动记录（Health Connect）：类型、起止、时长、距离与消耗，默认 7 天，最多 30 天。",
    parameters: {
      type: "object",
      properties: {
        days: { type: "integer", description: "天数，默认 7，范围 1–30" },
      },
      required: [],
    },
  },
  {
    name: "health_nutrition",
    description: "读取最近 N 天饮食（Health Connect）：每餐热量与蛋白质/脂肪/碳水及每日汇总，默认 7 天，最多 30 天。",
    parameters: {
      type: "object",
      properties: {
        days: { type: "integer", description: "天数，默认 7，范围 1–30" },
      },
      required: [],
    },
  },
  {
    name: "health_weekly_report",
    description: "聚合最近 N 天全量健康数据（步数/睡眠/心率/血氧/体重/运动/营养，含环比趋势），用于生成周报、健康趋势与运动建议；默认 7 天，最多 28 天。",
    parameters: {
      type: "object",
      properties: {
        days: { type: "integer", description: "天数，默认 7，范围 1–28" },
      },
      required: [],
    },
  },
  {
    name: "open_settings",
    description:
      "打开 Android 系统设置的具体页面（WiFi/蓝牙/显示/声音/电池/存储/应用/位置/安全/日期时间/开发者选项等），只跳转不修改。用户说「打开XX设置」时使用。",
    parameters: {
      type: "object",
      properties: {
        panel: {
          type: "string",
          description:
            "wifi | bluetooth | display | sound | volume | battery | storage | apps | app_details | location | security | privacy | date_time | nfc | airplane | developer | accessibility | input | data_usage | notification | home | cast | vpn | print | overlay | main",
        },
        package: { type: "string", description: "可选：app_details 时指定应用包名" },
      },
      required: ["panel"],
    },
  },
  {
    name: "mic_record",
    description:
      "录制麦克风音频并保存为 M4A 或 16kHz 单声道 WAV；返回路径、时长、格式和音频库 URI。",
    parameters: {
      type: "object",
      properties: {
        seconds: { type: "integer", description: "录音时长（秒），默认 10，范围 1-300" },
        format: { type: "string", enum: ["m4a", "wav"], description: "文件格式，默认 m4a" },
      },
      required: [],
    },
  },
  {
    name: "speech_recognize",
    description:
      "调用系统语音识别录一段用户语音并转成文字；返回识别文本、置信度、语言和耗时。",
    parameters: {
      type: "object",
      properties: {
        timeout_s: { type: "integer", description: "等待用户说完的上限秒数，默认 15，范围 5-60" },
        language: { type: "string", description: "识别语言 BCP-47 标签，如 zh-CN、en-US，默认 zh-CN" },
      },
      required: [],
    },
  },
  {
    name: "phone_call",
    description:
      "拨打电话：直接拨打或打开拨号盘预填号码（无权限时由用户按通话键确认）。",
    parameters: {
      type: "object",
      properties: { number: { type: "string", description: "电话号码" } },
      required: ["number"],
    },
  },
  {
    name: "sms_send",
    description: "发送短信到指定号码（需要 SEND_SMS 权限，首次调用会弹授权）。",
    parameters: {
      type: "object",
      properties: {
        number: { type: "string", description: "接收方号码" },
        text: { type: "string", description: "短信内容" },
      },
      required: ["number", "text"],
    },
  },
  {
    name: "location_get",
    description:
      "获取当前 GPS 位置（经纬度/精度/海拔/速度）。首次调用会弹定位权限；室内可能无 GPS，会回退网络定位或说明原因。",
    parameters: {
      type: "object",
      properties: {
        max_age_seconds: { type: "integer", description: "允许的缓存位置最大年龄（秒），默认 60" },
      },
      required: [],
    },
  },
  {
    name: "location_status",
    description: "查询定位服务状态（GPS/网络/被动提供器开关与最后已知位置），不触发定位不弹窗。",
    parameters: { type: "object", properties: {}, required: [] },
  },
  {
    name: "file_pick",
    description:
      "打开系统文件选择器让用户挑选文件，返回所选文件的 Uri 列表；用户取消或超时（120s）会明确返回。",
    parameters: {
      type: "object",
      properties: {
        mime: { type: "string", description: "MIME 过滤，如 image/*、application/pdf；默认全部" },
        multiple: { type: "boolean", description: "是否允许多选，默认 false" },
      },
      required: [],
    },
  },
  {
    name: "image_save",
    description: "把 base64 图片保存到系统相册 Pictures/AndAgent，保存后相册立即可见。",
    parameters: {
      type: "object",
      properties: {
        base64: { type: "string", description: "图片 base64（可含 data:image/...;base64, 前缀）" },
        filename: { type: "string", description: "可选：文件名" },
        display_name: { type: "string", description: "可选：相册显示名" },
        share: { type: "boolean", description: "可选：保存后直接打开系统分享面板" },
      },
      required: ["base64"],
    },
  },
  {
    name: "open_folder",
    description:
      "打开系统文件管理器定位到指定目录（downloads/documents/pictures/movies 或绝对路径），只浏览不修改。",
    parameters: {
      type: "object",
      properties: {
        dir: { type: "string", description: "downloads | documents | pictures | movies | alarms | notifications 或绝对路径" },
      },
      required: ["dir"],
    },
  },
  {
    name: "open_uri",
    description:
      "通过 URI/URL 跳转到其他应用或页面：网页链接、应用深链（taobao:// weixin:// alipays:// bilibili:// 等任意 scheme）、geo: 地图、mailto: 邮件、market:// 应用商店等。用户说「打开淘宝看看X」「跳转到XX应用」时使用。",
    parameters: {
      type: "object",
      properties: {
        uri: { type: "string", description: "要打开的 URI（https:// 或 scheme://）" },
        package: { type: "string", description: "可选：限定目标应用包名（深链歧义时用）" },
        action: { type: "string", description: "可选：自定义 Intent action，默认 VIEW" },
      },
      required: ["uri"],
    },
  },
  {
    name: "intent_send",
    description:
      "发送任意 Android Intent（通用版 open_uri）：指定 action 构造意图并发给系统，支持拍照（android.media.action.IMAGE_CAPTURE）、分享（SEND）、选择（PICK）等任意 action，可带 uri/mime/extras/categories/component。不确定设备上谁能响应先 intent_resolve 探查；标准 scheme（https:// taobao:// geo: 等）优先 open_uri。",
    parameters: {
      type: "object",
      properties: {
        action: { type: "string", description: "Intent action，如 android.intent.action.SEND、android.media.action.IMAGE_CAPTURE" },
        uri: { type: "string", description: "可选：data 部分，任意 scheme" },
        mime_type: { type: "string", description: "可选：MIME 类型" },
        extras: { type: "object", description: "可选 JSON 对象：key → string/int/bool/double/字符串数组" },
        categories: { type: "array", items: { type: "string" }, description: "可选，如 [\"android.intent.category.LAUNCHER\"]" },
        package: { type: "string", description: "可选：限定目标应用包名" },
        component: { type: "string", description: "可选：\"pkg/.Class\" 精确指定接收方（仅 exported activity 可外部调用）" },
        flags: { type: "integer", description: "可选：额外 Intent flags；默认已自动加 NEW_TASK" },
        mode: { type: "string", description: "chooser（默认，弹选择器）| direct（唯一接收方直接发）| confirm（只列接收方不发送）" },
      },
      required: ["action"],
    },
  },
  {
    name: "intent_resolve",
    description: "查询设备上哪些应用能响应指定 action 的 Intent（探查「这个动作手机能干吗」），返回接收方应用列表，不执行动作。",
    parameters: {
      type: "object",
      properties: {
        action: { type: "string", description: "要探查的 action，如 android.intent.action.SEND" },
        uri: { type: "string", description: "可选：data 部分" },
        mime_type: { type: "string", description: "可选：MIME 类型" },
      },
      required: ["action"],
    },
  },
  {
    name: "intent_broadcast",
    description: "发送自定义广播（仅限自定义/厂商 action，android. 开头的系统保护广播被拦截；接收方需 exported 才能收到，否则静默无效）。",
    parameters: {
      type: "object",
      properties: {
        action: { type: "string", description: "自定义广播 action，如 com.example.MY_EVENT" },
        extras: { type: "object", description: "可选 JSON 对象：key → string/int/bool/double/字符串数组" },
        package: { type: "string", description: "可选：限定显式广播目标包名" },
      },
      required: ["action"],
    },
  },
  {
    name: "album_photos",
    description:
      "查询相册照片（返回 id、content:// uri、文件名等，缩略图由工具卡片自动展示）。回复里用文字或表格列出文件名，不要输出 ```photos 围栏或复制图片数据。按文件夹/关键词过滤，默认最新 8 张。",
    parameters: {
      type: "object",
      properties: {
        bucket: { type: "string", description: "相册文件夹名（如 Screenshots/Camera）" },
        keyword: { type: "string", description: "文件名关键词" },
        limit: { type: "integer", description: "返回数量，默认 8，上限 20" },
        thumbnail: { type: "string", description: "是否带缩略图（true/false，默认 true）" },
      },
      required: [],
    },
  },
  {
    name: "album_open",
    description: "在系统图库中打开一张照片（album_photos 返回的 id 或 uri）。",
    parameters: {
      type: "object",
      properties: {
        id: { type: "string", description: "照片 id（album_photos 返回）" },
        uri: { type: "string", description: "或直接给 content:// uri" },
      },
      required: [],
    },
  },
  { name: "image_info", description: "读取图片宽高、格式、大小和 EXIF 方向。source 可用 content:// URI、相册 id、本地路径或 base64。", parameters: { type: "object", properties: { source: { type: "string" } }, required: ["source"] } },
  { name: "image_preview", description: "不保存，仅预览图片和 ops_json 编辑效果；预览图由工具卡片自动展示，回复只需用文字说明效果。", parameters: { type: "object", properties: { source: { type: "string" }, ops_json: { type: "string", description: "JSON 操作数组" } }, required: ["source"] } },
  { name: "image_edit", description: "按顺序编辑图片并保存。预览图由工具卡片自动展示，回复只需说明操作与 savedPath（可作为下次 source），不要输出 ```image 围栏或粘贴图片数据。ops_json 例如 [{\\\"op\\\":\\\"crop\\\",\\\"aspect\\\":\\\"16:9\\\"},{\\\"op\\\":\\\"brightness\\\",\\\"value\\\":20}]。支持 \" + IMAGE_EDIT_OPS.join(\"/\") + \"。R109 新增 flip{axis:h|v|both} / rotate_free{degrees,color} / text_draw{text 仅 ASCII≤64字,sizePx,color,alpha,anchor 九宫格或 x/y,rotation} / gaussian_blur{radius,x,y,w,h 区域} / vignette{strength,color} / auto_enhance。", parameters: { type: "object", properties: { source: { type: "string" }, ops_json: { type: "string" } }, required: ["source", "ops_json"] } },
  { name: "image_collage", description: "拼图并保存；预览图由工具卡片自动展示，回复只需说明布局与 savedPath。ids_or_uris 是图片源 JSON 数组；free 模式用 slots_json 指定每张图的 x/y/w/h。", parameters: { type: "object", properties: { ids_or_uris: { type: "string" }, layout: { type: "string", enum: ["grid2x2", "hstack", "vstack", "free"] }, output: { type: "string", enum: ["jpeg", "png"] }, slots_json: { type: "string" }, spacing: { type: "integer" }, background: { type: "string" }, corners: { type: "integer" } }, required: ["ids_or_uris", "layout"] } },
  { name: "ocr_text", description: "识别图片中的文字（OCR，ML Kit 中文离线模型内置 APK，全程本地推理）。source 传图片路径 / content:// URI / 相册图片 id / base64，可直接接 screen_capture / camera_capture 返回的 path。返回 status(OK|NO_TEXT)、text、lines（每行 text/box/lang，box 为 [left,top,width,height]）、blocks 可选按段落分组。NO_TEXT 时如实说明图中无文字，不要编造内容。", parameters: { type: "object", properties: { source: { type: "string" }, lines: { type: "boolean", description: "返回逐行文本与坐标，默认 true" }, blocks: { type: "boolean", description: "按段落分组返回，默认 false" } }, required: ["source"] } },
  { name: "db_create", description: "创建持久化 SQLite 数据库。", parameters: { type: "object", properties: { name: { type: "string" } }, required: ["name"] } },
  { name: "db_list", description: "列出 SQLite 数据库。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "db_gui", description: "在对话中打开指定数据库的只读图形浏览器。先用 db_list 查库名。", parameters: { type: "object", properties: { name: { type: "string" } }, required: ["name"] } },
  { name: "db_tables", description: "列出数据库的表、列数和行数。", parameters: { type: "object", properties: { name: { type: "string" } }, required: ["name"] } },
  { name: "db_schema", description: "查看表结构。", parameters: { type: "object", properties: { name: { type: "string" }, table: { type: "string" } }, required: ["name", "table"] } },
  { name: "db_exec", description: "执行参数化 SQL。值使用 ? 占位符和 params_json JSON 数组。DROP TABLE、无 WHERE DELETE 需 confirm=true。", parameters: { type: "object", properties: { name: { type: "string" }, sql: { type: "string" }, params_json: { type: "string" }, confirm: { type: "boolean" } }, required: ["name", "sql"] } },
  { name: "db_query", description: "只读 SELECT，返回最多 limit 行。值使用 ? 和 params_json JSON 数组。", parameters: { type: "object", properties: { name: { type: "string" }, sql: { type: "string" }, params_json: { type: "string" }, limit: { type: "integer" } }, required: ["name", "sql"] } },
  { name: "db_drop", description: "删除整个数据库，必须 confirm=true。", parameters: { type: "object", properties: { name: { type: "string" }, confirm: { type: "boolean" } }, required: ["name"] } },
  { name: "kv_get", description: "读取 KV 键值（namespace 默认 default），返回值与过期时间；不存在时返回 found=false。", parameters: { type: "object", properties: { namespace: { type: "string", description: "命名空间，默认 default" }, key: { type: "string", description: "键名，仅字母数字._-，最长 64" } }, required: ["key"] } },
  { name: "kv_set", description: "写入 KV 键值。value_json 为任意 JSON 值字符串；ttl_ms 为可选过期毫秒数；items_json 可传对象批量写入。", parameters: { type: "object", properties: { namespace: { type: "string", description: "命名空间，默认 default" }, key: { type: "string" }, value_json: { type: "string", description: "任意 JSON 值字符串，如 \"text\"、42、true、{\"a\":1}、[1,2]" }, ttl_ms: { type: "integer", description: "可选：过期毫秒数，正整数" }, items_json: { type: "string", description: "可选：{\"key\":值} JSON 对象批量写入；与 key/value_json 二选一" } }, required: [] } },
  { name: "kv_delete", description: "删除 KV 键；键不存在时返回错误。", parameters: { type: "object", properties: { namespace: { type: "string" }, key: { type: "string" } }, required: ["key"] } },
  { name: "kv_keys", description: "列出命名空间下的键名及剩余 TTL 秒数，可选 prefix 前缀过滤。", parameters: { type: "object", properties: { namespace: { type: "string" }, prefix: { type: "string", description: "可选：键名前缀" } }, required: [] } },
  { name: "kv_namespaces", description: "列出所有 KV 命名空间及其键数量。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "kv_clear", description: "清空整个命名空间的所有键，必须 confirm=true。", parameters: { type: "object", properties: { namespace: { type: "string", description: "要清空的命名空间" }, confirm: { type: "boolean" } }, required: ["namespace"] } },
  { name: "kv_export", description: "按命名空间导出 KV 备份 JSON（省略 namespace 或传 all 导出全部，all 永不包含 secure 敏感命名空间；全部导出上限 512KB）。只读操作。", parameters: { type: "object", properties: { namespace: { type: "string", description: "命名空间；省略或 all 导出全部（all 永不包含 secure）" }, include_expired: { type: "boolean", description: "是否包含已过期键，默认 false" }, include_secure: { type: "boolean", description: "仅当显式导出 secure 命名空间时设为 true，默认 false" } }, required: [] } },
  { name: "kv_import", description: "导入 kv_export 输出的 JSON 备份。merge（默认）同名键覆盖、其余保留；replace 先清空目标命名空间，必须 confirm=true。", parameters: { type: "object", properties: { data_json: { type: "string", description: "kv_export 输出的 JSON 字符串" }, mode: { type: "string", description: "merge（默认，同名覆盖）或 replace（先清空目标命名空间）" }, confirm: { type: "boolean", description: "mode=replace 时必须为 true" } }, required: ["data_json"] } },
  { name: "memory_add", description: "保存长期用户记忆；core 是长期人格记忆，用户偏好/重要指令写这里；满了先删过期条目再写新的。instruction 默认进 core。", parameters: { type: "object", properties: { content: { type: "string" }, category: { type: "string", enum: ["preference", "fact", "instruction"] }, core: { type: "boolean" } }, required: ["content"] } },
  { name: "memory_search", description: "按关键词搜索长期记忆。", parameters: { type: "object", properties: { query: { type: "string" }, limit: { type: "integer" } }, required: ["query"] } },
  { name: "memory_update", description: "按 id 更新记忆，支持自编辑 core。", parameters: { type: "object", properties: { id: { type: "string" }, content: { type: "string" } }, required: ["id", "content"] } },
  { name: "memory_delete", description: "按 id 删除记忆。", parameters: { type: "object", properties: { id: { type: "string" } }, required: ["id"] } },
  { name: "memory_delete_all", description: "清空全部或指定分类记忆。这是破坏性操作：仅当用户明确提出清空/删除全部记忆时才可传 confirm=true，禁止自行决定清空。", parameters: { type: "object", properties: { category: { type: "string" }, confirm: { type: "boolean" } }, required: ["confirm"] } },
  { name: "memory_get_all", description: "列出记忆。", parameters: { type: "object", properties: { core_only: { type: "boolean" } } } },
  { name: "memory_history", description: "查看记忆增改删历史。", parameters: { type: "object", properties: { id: { type: "string" } } } },
  { name: "memory_stats", description: "查看记忆数量和容量。", parameters: { type: "object", properties: {} } },
  ...EXTRA_TOOL_DEFS,
  {
    name: "android_reflect",
    description: "受限反射桥：查询白名单 Android 系统 API；对象结果不可二次反射。",
    parameters: { type: "object", properties: {
      clazz: { type: "string", description: "白名单完整类名" },
      method: { type: "string", description: "白名单方法名或 Build 常量名" },
      args_json: { type: "string", description: "JSON 数组；基本类型、null、$context/$contentResolver 占位符" },
      static: { type: "boolean", description: "是否为静态调用" },
      confirm: { type: "boolean", description: "调用 setStreamVolume 时必须为 true" },
    }, required: ["clazz", "method"] },
  },
  ...apiTools.map(({ name, description, params }) => ({
    name, description,
    parameters: { type: "object", properties: Object.fromEntries(params.map(p => [p.name, {
      type: p.type === "int" || p.type === "long" ? "integer" : p.type === "float" || p.type === "double" ? "number" : p.type,
      description: p.description,
    }])), required: params.map(p => p.name) },
  })),
  // R65: AirPods BLE 监听（pods/PODS_TOOLS，Kotlin 侧 PodsTool 注册）
  { name: "airpods_scan", description: "扫描附近 AirPods/Beats 的公开 BLE 广播，返回型号、电量、佩戴、盒盖和连接态。", parameters: { type: "object", properties: { duration_ms: { type: "integer", description: "扫描毫秒，默认 3000，范围 1000-30000" } } } },
  { name: "airpods_status", description: "查询最近 30 秒内主用耳机广播快照（型号/佩戴/盒盖/电量/连接态）。", parameters: { type: "object", properties: {} } },
  { name: "airpods_battery", description: "查询主用耳机左、右、盒电量与充电状态。", parameters: { type: "object", properties: {} } },
  { name: "airpods_monitor_start", description: "开启前台服务持续监听 AirPods 广播；最新值和历史写入 airpods KV 命名空间。", parameters: { type: "object", properties: { interval_ms: { type: "integer", description: "KV 保存间隔毫秒，默认 5000" } } } },
  { name: "airpods_monitor_stop", description: "停止 AirPods 前台监听。", parameters: { type: "object", properties: {} } },
  { name: "airpods_history", description: "读取监听期间最近一段时间的 AirPods 电量与佩戴历史。", parameters: { type: "object", properties: { duration_min: { type: "integer", description: "历史分钟数，默认 30，范围 1-30" } } } },
  { name: "media_sessions", description: "列出活动媒体会话；需通知使用权限。", parameters: { type: "object", properties: {} } },
  { name: "media_events_on", description: "开启媒体播放状态和曲目监听，写入 media_events KV；需通知使用权限。", parameters: { type: "object", properties: {} } },
  { name: "media_events_off", description: "停止媒体事件监听。", parameters: { type: "object", properties: {} } },
  { name: "media_events_read", description: "读取最近媒体事件。", parameters: { type: "object", properties: { duration_min: { type: "integer" } } } },
  { name: "media_send_key", description: "发送媒体按键 play/pause/toggle/next/prev。", parameters: { type: "object", properties: { keycode: { type: "string" } }, required: ["keycode"] } },
  { name: "notif_events_on", description: "开启本地通知事件记录；需通知使用权限。", parameters: { type: "object", properties: { exclude_apps: { type: "array", items: { type: "string" } } } } },
  { name: "notif_events_off", description: "停止通知事件记录。", parameters: { type: "object", properties: {} } },
  { name: "notif_events_read", description: "读取最近通知事件，可按包名过滤。", parameters: { type: "object", properties: { duration_min: { type: "integer" }, package: { type: "string" } } } },
  { name: "notif_permission", description: "打开系统通知使用权限设置。", parameters: { type: "object", properties: {} } },
  { name: "audio_events_on", description: "监听耳机和音频设备添加、移除，写入 audio_events KV。", parameters: { type: "object", properties: {} } },
  { name: "audio_events_off", description: "停止音频设备监听。", parameters: { type: "object", properties: {} } },
  { name: "audio_events_read", description: "读取最近音频设备事件。", parameters: { type: "object", properties: { duration_min: { type: "integer" } } } },
  { name: "ir_available", description: "检测手机是否有红外发射器，返回支持的载波范围。", parameters: { type: "object", properties: {} } },
  { name: "ir_send_raw", description: "按 carrier_hz 载波发送交替 on/off 微秒数组 pattern。真实发射红外信号。", parameters: { type: "object", properties: { carrier_hz: { type: "integer" }, pattern: { type: "array", items: { type: "integer" } } }, required: ["carrier_hz", "pattern"] } },
  { name: "ir_send_pronto", description: "发送 Pronto Hex 0000 原始调制码。真实发射红外信号；压缩 RC5/RC6 码需先转为 0000 原始码。", parameters: { type: "object", properties: { hex: { type: "string" } }, required: ["hex"] } },
  { name: "ir_code_search", description: "在红外码表中按品牌/设备/按键检索遥控码（内置 LG/三星/Sony/东芝/日立/三洋/海信/TCL/海尔 电视、美的空调、万播投影，来自 Flipper-IRDB，另含导入的用户码）。指定 command 时返回该按键的 Pronto Hex，可交给 ir_send_pronto 或 ir_code_send。", parameters: { type: "object", properties: { brand: { type: "string", description: "品牌，如 LG/三星/Sony/日立/三洋/海信" }, device: { type: "string", description: "设备类型，如 tv/ac，默认不限" }, query: { type: "string", description: "自由关键词：品牌/设备/按键（支持中英文）" }, command: { type: "string", description: "按键规范名，如 power/vol_up/mute/ch_up/ok/input" }, limit: { type: "integer", description: "返回条数，默认 8，上限 20" } } } },
  { name: "ir_code_send", description: "按品牌+设备+按键从码表取码并真实发射（NEC/NECx/Sony/RC5 参数现场编译）。先 ir_available 确认有发射器；码表没有的设备先用 ir_code_import 批量导入或 ir_code_save 单条保存。", parameters: { type: "object", properties: { brand: { type: "string", description: "品牌，如 LG/三星/Sony/东芝/日立/三洋/海信/TCL/海尔/美的/万播" }, device: { type: "string", description: "设备类型，默认 tv" }, command: { type: "string", description: "按键，如 power/音量+/确定" }, frames: { type: "integer", description: "连发帧数 1-5，省略用协议默认（Sony 3，其余 2）" } }, required: ["brand", "command"] } },
  { name: "ir_code_save", description: "把遥控码保存为用户码表条目（写入 ir_codes KV，同名品牌+设备+按键覆盖内置值）。pronto、carrier_hz+pattern、protocol+code 三选一。", parameters: { type: "object", properties: { brand: { type: "string" }, device: { type: "string", description: "设备类型，默认 tv" }, command: { type: "string", description: "按键名，如 power/vol_up 或自定义英文名" }, model: { type: "string" }, pronto: { type: "string", description: "Pronto Hex 0000 码" }, carrier_hz: { type: "integer", description: "载波频率 Hz，与 pattern 搭配，默认 38000" }, pattern: { type: "array", items: { type: "integer" }, description: "交替 on/off 微秒数组" }, protocol: { type: "string", description: "nec1/nec2/necx1/necx2/sony12/sony15/sony20/rc5" }, code: { type: "integer", description: "协议功能值 0-255，与 protocol 搭配" }, device_no: { type: "integer", description: "协议设备号，省略沿用同品牌既有条目" }, subdevice_no: { type: "integer", description: "协议子设备号 / NECx 高字节" } }, required: ["brand", "command"] } },
  { name: "ir_code_import", description: "批量导入开源码表（irdb 风格 CSV/JSON）到用户码表（协议仅支持 NEC1/NEC2/NECx1/NECx2/Sony12/15/20/RC5，其余协议行会被跳过并如实返回）。引入的码表数据由来源许可约束。", parameters: { type: "object", properties: { brand: { type: "string", description: "品牌名（irdb 的 CSV 不含品牌，从路径指定）" }, device: { type: "string", description: "设备类型，默认 tv" }, csv: { type: "string", description: "irdb CSV 文本，每行 functionname,protocol,device,subdevice,function" }, rows_json: { type: "string", description: "或 JSON 数组 [{name,protocol,device,subdevice,function}]" } }, required: ["brand"] } },
  { name: "ir_code_delete", description: "删除用户保存的红外码（按 key 或 brand+device+command），不删除内置码表条目。", parameters: { type: "object", properties: { key: { type: "string", description: "ir_code_save 返回的 key" }, brand: { type: "string" }, device: { type: "string" }, command: { type: "string" } } } },
] satisfies PiTool[];

/**
 * Appended to whatever system prompt the user saved, so the reply language
 * always follows the language of the user's question.
 */
const LANGUAGE_GUIDE = [
  "语言约定：",
  "始终使用用户提问时所用的语言回复；用户在对话中切换语言时立即跟随切换。",
  "代码、命令、路径、参数名和标识符保持原样，不要翻译。",
].join("\n");

const LANGUAGE_GUIDE_EN = [
  "Language:",
  "Always reply in the language of the user's latest question, and switch immediately if they change languages mid-conversation.",
  "Keep code, commands, paths, argument names, and identifiers unchanged.",
].join("\n");

/**
 * Appended to whatever system prompt the user saved, so the chart protocol is
 * always in effect (stored prompts predate this feature).
 */
const CHART_GUIDE = [
  "数据可视化约定：",
  "位置结果用 ```geo JSON 块；设备、电池等键值状态用 ```kv 块。",
  "1. 当需要展示数据对比、趋势或占比时，在回复里插入 ```chart 围栏代码块，围栏内容必须是合法的 ECharts option JSON。",
  "2. 只能使用纯 JSON：禁止函数、注释、变量、单引号或 JS 表达式（例如 formatter 函数一律不要写）。",
  "3. 图表类型仅限 bar、line、pie，用 series[].type 指定；数据标签直接用字符串/数字数组。",
  "4. 工具返回 JSON 时，先读懂数据，再把整理后的 option 放进 chart 块，不要直接粘贴大段原始 JSON。",
  "5. 数学公式用 LaTeX 书写：行内用 $...$，块级用 $$...$$；流程图、时序图、架构图用 ```mermaid 围栏。",
  "6. 示例：",
  "```chart",
  '{"tooltip":{},"legend":{},"xAxis":{"type":"category","data":["一月","二月","三月"]},"yAxis":{"type":"value"},"series":[{"name":"照片数","type":"bar","data":[12,20,8]}]}',
  "```",
].join("\n");

const LIVE_GUIDE = [
  "实时画布与循环（```live 围栏）：",
  "- 用户要实时曲线、动态图表、持续监控、时钟或动画可视化时，输出 ```live 围栏，内容为合法 JSON：{\"name\":\"标题\",\"interval_ms\":1000,\"code\":\"JS 循环体\"}。卡片由用户点击启动；可加 render 字段，用 canvas JS 接收 ctx,t,w,h,data 绘制实时数据。",
  "- code 每 tick 执行一次：andagent.tool(name, JSON.stringify(args)) 取设备数据（如 sensor_read、battery_detail、android_reflect）；andagent.kv.get(namespace,key) / set(namespace,key,value) 保存跨 tick 状态；live.result(data) 提交本轮结果（JSON ≤16KB）。",
  "- live 脚本每 tick 是新 JS context。把业务逻辑包在 try { ... } catch (e) { live.result({error: String(e)}) } 中，将异常作为结构化数据展示；跨 tick 变量须从 andagent.kv 重新读取。",
  "- 未捕获异常会进入卡片的「错误历史」（环形保留最近 5 条，含 tick 序号、时间戳与 QuickJS 原生行号/堆栈），连续 5 次失败自动停止；所以业务逻辑务必用 try/catch 包裹，并在 catch 里主动 live.result 上报，不要依赖错误历史兜底。",
  "- 实时加速度示例：{\"name\":\"加速度\",\"interval_ms\":200,\"code\":\"const s=JSON.parse(andagent.tool('sensor_read',JSON.stringify({sensor:'accelerometer'})));const pts=andagent.kv.get('live_accel','pts')||[];pts.push(s.values.slice(0,3));const next=pts.slice(-60);andagent.kv.set('live_accel','pts',next);live.result({type:'line',points:next})\"}。根据 sensor_read 实际返回结构提取轴值。",
  "- 要画实时曲线时必须提供 render：从 data.points 读点，使用 ctx.beginPath / moveTo / lineTo / stroke 在 canvas 上绘制；省略 render 时卡片只显示本轮 JSON 文本。",
  "- 单 tick 500ms 硬超时；连续 5 次异常自动停止；用户可在卡片上暂停、停止或重启。",
  "- 静态 canvas 2D 绘图用 ```canvas 围栏，内容可为 JS 或 {\"code\":\"...\",\"animate\":true} JSON；运行在 sandbox iframe，代码接收 ctx,t,w,h，animate:true 可随 requestAnimationFrame 重绘。",
  "- 完整 HTML 页面/富文本/CSS 动画/可交互组件用 ```html 围栏：内容为完整 HTML 文档或 body 片段，原样渲染在 sandbox iframe（零代码执行限制同 canvas，支持触摸/点击/表单交互）。需要表单收集输入时优先 a2ui 围栏。",
].join("\n");

const CHART_GUIDE_EN = [
  "Data visualization rules:",
  "Use ```geo JSON for locations and ```kv for device or battery key-value status.",
  "1. For comparisons, trends, or proportions, add a ```chart fence containing valid ECharts option JSON.",
  "2. Use pure JSON only: no functions, comments, variables, single quotes, or JavaScript expressions.",
  "3. Chart series types are limited to bar, line, and pie, specified with series[].type. Use string or number arrays for labels and values.",
  "4. Read tool JSON, summarize the data, and place the prepared option in the chart fence. Do not paste raw JSON output.",
  "5. Use LaTeX for formulas ($...$ inline or $$...$$ blocks); use ```mermaid for flow, sequence, or architecture diagrams.",
  "6. Example:",
  "```chart",
  '{"tooltip":{},"legend":{},"xAxis":{"type":"category","data":["Jan","Feb","Mar"]},"yAxis":{"type":"value"},"series":[{"name":"Photos","type":"bar","data":[12,20,8]}]}',
  "```",
].join("\n");

const LIVE_GUIDE_EN = [
  "Live canvas and loops (```live fence):",
  "- For live curves, dynamic charts, ongoing monitoring, clocks, or animated visuals, output a ```live fence with valid JSON: {\"name\":\"Title\",\"interval_ms\":1000,\"code\":\"JS loop body\"}. The user starts the card. An optional render field contains canvas JS receiving ctx,t,w,h,data.",
  "- code runs once per tick. andagent.tool(name, JSON.stringify(args)) reads device data (such as sensor_read, battery_detail, android_reflect); andagent.kv.get(namespace,key) and set(namespace,key,value) persist state across ticks; live.result(data) submits the JSON result (up to 16 KB).",
  "- Each tick uses a fresh JS context. Wrap business logic in try { ... } catch (e) { live.result({error: String(e)}) } to return structured errors; reload cross-tick values from andagent.kv.",
  "- Uncaught exceptions land in the card's error history (a 5-entry ring buffer with tick number, timestamp and the QuickJS native line/stack); five consecutive failures auto-stop the loop. Always wrap business logic in try/catch and report errors with live.result yourself instead of relying on the history.",
  "- Accelerometer example: {\"name\":\"Acceleration\",\"interval_ms\":200,\"code\":\"const s=JSON.parse(andagent.tool('sensor_read',JSON.stringify({sensor:'accelerometer'})));const pts=andagent.kv.get('live_accel','pts')||[];pts.push(s.values.slice(0,3));const next=pts.slice(-60);andagent.kv.set('live_accel','pts',next);live.result({type:'line',points:next})\"}.",
  "- For a live curve include render: read data.points and draw with ctx.beginPath, moveTo, lineTo, and stroke. Without render the card shows JSON text.",
  "- Each tick has a hard 500ms timeout. Five consecutive failures stop the loop. The user can pause, stop, or restart it from the card.",
  "- For static 2D drawing, output a ```canvas fence with JS or JSON {\"code\":\"...\",\"animate\":true}. It runs in a sandbox iframe with ctx,t,w,h; animate:true redraws with requestAnimationFrame.",
  "- For full HTML pages, rich text, CSS animations, or interactive components, use an ```html fence: complete document or body fragment, rendered as-is in a sandboxed iframe (same zero-code-execution model as canvas; touch/click/form interactions work). Prefer the a2ui fence when collecting structured form input.",
].join("\n");

/**
 * 自我扩展（P1）：教模型在能力缺口时用 my_tool_create 生成可复用的新工具。
 */
const SELF_EXT_GUIDE = [
  "自我扩展（生成新工具）：",
  "- 仅当现有工具确实无法完成任务、且该能力预期会再次用到时，才用 my_tool_create 创建新工具；一次性任务不要建工具。",
  "- 创建前先向用户简要说明：要建什么工具、为什么现有工具组合不够。创建成功后，下一轮对话即可直接调用该 my_* 工具；用 my_tool_test 验证后再正式使用。",
  "- 工具形态：code 是 exports.tools = [{ name: \"run\", handler: function(args){...} }]；handler 内用 andagent.tool(name, JSON.stringify(args)) 调用 allow 白名单声明的现有工具（如 http_request、db_query、web_read），andagent.kv / andagent.fs / andagent.db 可存取状态，return 结果（可 JSON 序列化）。顶层代码保持纯净，不要在顶层调用工具。",
  "- parameters_json 写参数 JSON Schema（type/properties/required），description 写清楚何时使用、返回什么，让以后的对话能直接用对。",
  "- 管理用 my_tool_list / my_tool_read / my_tool_update（改代码需 confirm=true）/ my_tool_delete / my_tool_enable / my_tool_share（导出技能 ZIP 供另一台设备 skill_install）；上限 20 个，禁止 skill_/mcp_/cron_/live_/script_/my_ 前缀工具进白名单。",
].join("\n");

const SELF_EXT_GUIDE_EN = [
  "Self-extension (tool generation):",
  "- Only when existing tools genuinely cannot complete the task AND the capability is likely to be reused, create a new tool with my_tool_create. Do not create tools for one-off tasks.",
  "- Before creating, briefly tell the user what tool you will build and why existing tools are insufficient. After creation it becomes callable in the next turn; verify with my_tool_test before real use.",
  "- Tool shape: code is exports.tools = [{ name: \"run\", handler: function(args){...} }]. Inside the handler call allowlisted tools with andagent.tool(name, JSON.stringify(args)) (e.g. http_request, db_query, web_read); use andagent.kv / andagent.fs / andagent.db for state; return a JSON-serializable result. Keep top-level code pure; never call tools at top level.",
  "- parameters_json is the arguments JSON Schema (type/properties/required); description must state when to use it and what it returns so later conversations use it correctly.",
  "- Manage with my_tool_list / my_tool_read / my_tool_update (code changes need confirm=true) / my_tool_delete / my_tool_enable / my_tool_share (exports a skill ZIP installable via skill_install on another device); max 20 tools; skill_/mcp_/cron_/live_/script_/my_ prefixed tools are forbidden in the allowlist.",
].join("\n");

/**
 * R101：网页开发闭环提示词。教 AI「生成网页 → 落盘 → 嵌入 → 用 CDP 查错/验证 → 修 → 交付」，
 * 而不是只在回复里贴一段代码让用户自己贴到浏览器。参考 CHART_GUIDE / LIVE_GUIDE 模式，
 * 中英双语，随语言注入 systemPrompt。
 */
const WEB_DEV_GUIDE = [
  "网页 / H5 页面开发工作流（**不要只给代码让用户自己贴到浏览器**）：",
  "1. 用户要开发网页、H5 页面、单页应用或可视化页面时，直接生成一份完整 HTML 文档（含内联 <style> / <script>，尽量不依赖外部 CDN 也能运行），而不是只在回复里贴代码片段。",
  "2. 用 file_write 把完整 HTML 存到 Download/andagent/web/ 下（例如 dir=downloads、filename=page.html），记下工具返回的 savedPath。",
  "3. 用 web_embed(uri=<该文件的绝对路径或 file:/// URI>) 打开；本地 HTML 会进入原生内嵌 WebView（stageLocalHtml 路径，是本进程 DevTools target）。",
  "4. 必要时先用 devtools_debugging(enable=true)，再用 devtools_status 确认页面已被枚举；用 devtools_errors / devtools_console 查加载期报错——console 从 attach 起实时监听，Log 域会补发页面加载期的错误。",
  "5. 用 devtools_eval 在页面上验证功能与 DOM（例如 document.title、document.querySelector(...)、关键元素数量、按钮点击后的状态；支持 awaitPromise）。",
  "6. 若有报错：用 file_write 覆盖修改本地 HTML 文件，再 web_embed 打开新页面复查；重复 4-5 直到 devtools_errors 为空且功能验证通过。",
  "7. 全绿后交付：明确告知 savedPath、页面功能与验证结果；需要展示时可用 web_embed 或 ```html 围栏预览。",
  "要点：```html 围栏适合静态预览，但「生成 → 嵌入 → 查错 → 修」闭环必须走 file_write + web_embed + devtools_*，这样才能用 CDP 真正确认页面在 WebView 里加载无误。",
].join("\n");

const WEB_DEV_GUIDE_EN = [
  "Web / H5 page development loop (do NOT just hand the user code to paste into a browser):",
  "1. When asked to build a web page, H5 page, single-page app, or visual page, produce a complete self-contained HTML document (inline <style> / <script>, ideally runnable without external CDNs), not just a code snippet in the reply.",
  "2. Save the full HTML with file_write into Download/andagent/web/ (e.g. dir=downloads, filename=page.html) and note the returned savedPath.",
  "3. Open it with web_embed(uri=<absolute path or file:/// URI>). A local HTML file is staged into a native embedded WebView (stageLocalHtml), which is a same-process DevTools target.",
  "4. If needed, enable devtools_debugging(enable=true), then use devtools_status to confirm the page is listed; use devtools_errors / devtools_console for load-time errors — console is captured live from attach, and the Log domain replays errors that happened during page load.",
  "5. Verify functionality and DOM with devtools_eval (e.g. document.title, document.querySelector(...), element counts, state after a button click; awaitPromise supported).",
  "6. On errors: overwrite the local HTML with file_write, reopen via web_embed, and repeat 4-5 until devtools_errors is empty and features pass.",
  "7. Deliver when green: state the savedPath, the page features, and the verification result; use web_embed or an ```html fence for preview.",
  "Note: an ```html fence is fine for a static preview, but the generate -> embed -> check -> fix loop must go through file_write + web_embed + devtools_* so CDP can actually confirm the page loads cleanly in the WebView.",
].join("\n");

const NETWORK_GUIDE = [
  "网络访问：http_request 是通用 HTTP 客户端，支持 GET/POST/PUT/DELETE/PATCH/HEAD，可用 headers_json 传 Authorization 等请求头、body 传请求体；它允许 http:// 与 https://，局域网设备（Home Assistant 的 http://192.168.x.x:8123/api/、ESPHome、路由器 API）优先用它，网页正文仍用 web_read（仅 HTTPS）。",
  "「这个网里有什么设备」先问 lan_discover（默认 protocol=both，SSDP + mDNS 并行约 3-5 秒）：返回每台设备的 name/category（tv 电视、speaker 音箱、printer 打印机、home_assistant、nas、camera、router、cast、computer、iot、andagent 另一台 AndAgent）、address/port 与 txt 属性；home_assistant 的 txt.base_url 或 location 就是它的 API 入口，拿到后直接用 http_request 访问。要更细的型号与可控制服务用 upnp_describe(url=该设备的 location)。",
  "只有 lan_discover 找不到（设备不发 SSDP/mDNS，如部分老式打印机或路由器）才退回 lan_scan：mode=ping 扫当前 Wi-Fi 的 /24，mode=port 查已知端口（8123、80、1883）。仅扫描自己所在的网络，不要扫描他人网段；不要用 http_request 对同一地址高频轮询。",
  "http_request 返回 JSON：status/ok/url/headers/body/truncated/elapsedMs。长正文用 max_chars 控制；非 2xx 时先看 status 与 body 前段再决定是否重试，不要盲目重复同一请求。",
].join("\n");

const WIFI_GUIDE = [
  "Wi-Fi 免密入网（DPP）：wifi_easy_connect 先 action=status 看能力（easyConnectSupported、systemScannerAvailable），支持再 action=scan 拉起系统 DPP 扫码器——本机扫路由器/另一台手机显示的二维码免密入网。拿到一段 DPP URI 文本时用 action=uri 解析（band/信道/MAC/公钥），qr=true 可复刻成二维码给别的手机扫。诚实边界：Android 公开 API 只有 enrollee（本机扫码入网）角色，本机不能当 configurator 给 IoT/摄像头下发凭据（@SystemApi 不可调）——ESPHome 设备的配网要引导用户走设备自身的 Improv/AP 模式流程，不要声称用 wifi_easy_connect 能配 ESPHome。",
  "Wi-Fi Direct（无路由器互连）：两台手机没有共同 Wi-Fi 时，一台 wifi_direct(action=create_group)（可指定 ssid/passphrase），另一台用「普通 Wi-Fi 连接」该热点即可；群主返回的 groupOwnerIp 可直接 http_request 通信（配合 R81 互传思路）。同网段 P2P 发现走 action=discover → connect（address=MAC）。注意：需要精确定位权限且定位开关打开，否则发现不到设备；用完 remove_group 清理。wifi_direct 是破坏性工具（会改网络连接），status 之外的调用前先向用户说明。",
  "Wi-Fi RTT 测距：wifi_rtt(action=range) 对周边 802.11mc AP 米级测距，返回距离/标准差/RSSI 与可靠性评级（high/medium/low 按标准差分档）；bssid 可指定某个 AP，include_non_mc=true 仅 Android 12+ 可用且精度差。设备缺 FEATURE_WIFI_RTT 或 isAvailable=false 时如实告知，不要编造距离。需要精确定位权限与定位开关。",
].join("\n");

const WIFI_GUIDE_EN = [
  "Wi-Fi passwordless join (DPP): call wifi_easy_connect with action=status first (easyConnectSupported, systemScannerAvailable), then action=scan to launch the system DPP scanner — the phone joins a network without typing a password by scanning the QR shown by a router or another phone. Given a DPP URI string, action=uri parses it (band/channel/MAC/public key) and qr=true re-renders it as a QR PNG for another phone. Honest boundary: the public Android API only offers the enrollee role (this phone joins a network); it cannot act as a configurator to hand credentials to IoT devices/cameras (@SystemApi is unreachable) — for ESPHome provisioning guide the user to the device's own Improv/AP-mode flow instead of claiming wifi_easy_connect can do it.",
  "Wi-Fi Direct (router-free P2P): when two phones share no Wi-Fi, one runs wifi_direct(action=create_group) (optionally with ssid/passphrase) and the other joins that hotspot with a plain Wi-Fi connection; the owner's groupOwnerIp works directly with http_request (feeds the R81 transfer flow). Same-range peer discovery uses action=discover then connect (address=MAC). Note: requires fine location and the location toggle on, otherwise no peers are found; clean up with remove_group. wifi_direct is destructive (changes network state) — explain before any call beyond status.",
  "Wi-Fi RTT ranging: wifi_rtt(action=range) ranges nearby 802.11mc APs to metre accuracy, returning distance/standard deviation/RSSI and a reliability grade (high/medium/low by std-dev); bssid targets one AP, include_non_mc=true is Android 12+ only and far less accurate. If the device lacks FEATURE_WIFI_RTT or isAvailable=false, say so — never invent distances. Requires fine location and the location toggle.",
].join("\n");

const NETWORK_GUIDE_EN = [
  "Network access: http_request is a generic HTTP client supporting GET/POST/PUT/DELETE/PATCH/HEAD, with headers_json for Authorization-style headers and body for payloads. It allows both http:// and https://, so use it first for LAN devices (Home Assistant at http://192.168.x.x:8123/api/, ESPHome, router APIs); keep web_read (HTTPS only) for web page text.",
  "For \"what is on this network\", start with lan_discover (protocol=both by default, SSDP + mDNS in parallel, ~3-5s). It returns name/category per device (tv, speaker, printer, home_assistant, nas, camera, router, cast, computer, iot, andagent = another AndAgent phone), address/port and TXT attributes; a home_assistant device's txt.base_url or location is its API entry, call it with http_request. Use upnp_describe(url=<that device's location>) for the model and controllable services (controlURL/SCPDURL).",
  "Fall back to lan_scan only when a device sends neither SSDP nor mDNS: mode=ping scans the current Wi-Fi /24, mode=port probes a known port (8123, 80, 1883). Scan only the network you are on, never someone else's range, and do not hammer one address through http_request.",
  "http_request returns JSON with status/ok/url/headers/body/truncated/elapsedMs. Bound long bodies with max_chars; on non-2xx inspect status and the body prefix before deciding whether to retry, and do not blindly repeat the same request.",
].join("\n");

const A11Y_GUIDE = [
  "无障碍控件自动化：先用 a11y_status 查看服务是否启用；未启用时引导用户到 系统设置 → 无障碍 → AndAgent 开启（Android 13+ 若提示受限设置，先在应用信息页允许受限设置），也可调用 open_settings(panel=accessibility) 跳转。",
  "操作目标应用前先把它切到前台，再调用 a11y_screen 读取控件树（默认 80 节点，含 viewId/text/contentDescription/class/bounds）。a11y_screen 只读，密码框内容不会返回；页面太大时用 max_nodes 限制，不要反复全量读屏。",
  "优先用控件选择器而不是坐标：a11y_click / a11y_long_click 支持 view_id（完整名或 :id/xxx 后缀）、text、content_description，match=contains（默认）/exact；匹配到不可点击节点会自动向上找可点击父节点，找不到会列出候选控件。",
  "滚动用 a11y_scroll（direction=forward/backward，可用选择器指定列表容器）；输入文本用 a11y_text（先 focus 再 setText，省略选择器则写当前焦点输入框）；返回/桌面/最近任务用 a11y_action。点击、长按、输入、全局动作会走现有确认闸。",
].join("\n");

const A11Y_GUIDE_EN = [
  "Accessibility control automation: check a11y_status first. When disabled, guide the user to Settings → Accessibility → AndAgent (on Android 13+, if restricted settings are mentioned, allow them from the app info page first), or call open_settings with panel=accessibility.",
  "Bring the target app to the foreground before a11y_screen, which returns the control tree (80 nodes by default with viewId/text/contentDescription/class/bounds). a11y_screen is read-only and never returns password fields; bound large screens with max_nodes and do not repeatedly dump the whole screen.",
  "Prefer selectors over coordinates: a11y_click / a11y_long_click accept view_id (full name or :id/xxx suffix), text and content_description, with match=contains (default)/exact. If the matched node is not clickable, the clickable ancestor is used; when nothing matches, candidates are listed.",
  "Use a11y_scroll for scrolling (direction=forward/backward, optional selector for the list container), a11y_text to type (focus then setText; focused editor when no selector), and a11y_action for back/home/recents. Click, long-click, text and global actions go through the normal confirmation gate.",
].join("\n");

const SSH_TRANSFER_GUIDE = [
  "SSH 文件传输：用户要服务器文件时，先用 ssh_remote_ls 查看远程目录，再用 ssh_download 下载单文件；返回 savedPath 后可交给 file_read 分析。目录可先用 ssh_exec 打包为 tar，再下载单文件。",
  "上传用 ssh_upload，先说明目标路径和覆盖风险。已存在的远程文件只有用户确认后才传 overwrite=true、confirm=true；默认不覆盖。local_path 只能是 Download/AndAgentSsh 或 app_private（ssh_downloads）里的文件，凭据等私有文件禁止上传。SSH 会话退后台由前台服务保活，断线后需用户重新连接。",
].join("\n");

const SSH_TRANSFER_GUIDE_EN = [
  "SSH file transfer: for a server file, inspect the remote directory with ssh_remote_ls, then download one file with ssh_download. Pass the returned savedPath to file_read for analysis. For a directory, first create a tar archive with ssh_exec and download that file.",
  "For ssh_upload, explain the destination and overwrite risk first. Set overwrite=true and confirm=true for an existing remote file only after the user confirms; never overwrite by default. local_path must be a file under Download/AndAgentSsh or app_private (ssh_downloads); credentials and other private app files are blocked. Background SSH sessions use a foreground keep-alive service; reconnect manually after a disconnect.",
].join("\n");

const HARDWARE_GUIDE = [
  "红外遥控：先用 ir_available 检测发射器。内置码表（Flipper-IRDB，CC0）收录 LG/三星/Sony/东芝/日立/三洋/海信/TCL/海尔 电视、美的空调、万播投影的电源、音量、频道、方向、确定、输入源、数字键、温度等常用键，中英文品牌与按键名都能搜：ir_code_search 查询 → ir_code_send 直接发射，返回结果里带 pronto 的码也可用 ir_send_pronto 发送。码表没有的设备按需补：a) 联网查到 Pronto Hex 0000 后 ir_code_save 单条保存；b) 整品牌批量导入：web_read 取开源码库（如 github.com/probonopd/irdb 的 codes/<品牌>/<设备>/<device>,<subdevice>.csv），把 CSV 原文交给 ir_code_import（brand 必填、device 默认 tv），只接受 NEC1/NEC2/NECx1/NECx2/Sony12/15/20/RC5 参数行，其余协议会被跳过并如实报告，引入时遵守来源许可；c) 自定义协议用 ir_send_raw 发交替 on/off 微秒数组。个别机型按一次触发两次时把 ir_code_send 的 frames 设为 1。空调等多状态设备需要含温度/模式/风速的完整状态帧，用 ir_code_save 保存整帧。手机无红外硬件时如实告知，不能声称已发送。",
  "AirPods：airpods_scan 扫描附近广播；airpods_status / airpods_battery 查询近期状态和电量。",
  "airpods_monitor_start 开启可从通知停止的后台监听；airpods_monitor_stop 停止；airpods_history 取电量与佩戴历史，可用 ```chart 画续航趋势。",
  "监听值写入 KV 的 airpods/latest 和 airpods/history。```live 循环可用 andagent.kv.get('airpods','latest') 读取；检测佩戴状态 true→false 时用 media_send_key(keycode=pause) 暂停。低电量可调用 notify_send；跨 tick 保存上次状态避免重复触发。",
  "系统事件：媒体会话与通知需先用 notif_permission 在系统设置授权，再开启 media_events_on / notif_events_on；音频设备用 audio_events_on。各通道的 KV 命名空间为 media_events、notif_events、audio_events，latest 与 history 键可供 live 轮询；使用 timestamp 去重。用对应 off 工具或前台通知停止监听。音频设备回调报告添加/移除，不保证获知实际音频路由切换。",
  "传感器与机身状态：要振动/姿态数据用 sensor_stream（默认 200Hz、多传感器并行落 CSV，返回每轴 stats 与 vibration 判定）；低功耗长序列用 sensor_batch（看 hardwareFifo/avgEventsPerBatch 判断是否真批量）；单点瞬时读数仍用 sensor_read。心率用 heart_rate——多数手机没有该硬件，缺失或 NO_DATA 时如实告知，不要编造数值。「有人动了再叫我」用 significant_motion（一次硬件触发，最长 300s）。发热/降频先看 thermal_status，充放电电流电压与功率用 battery_electrical。触感反馈用 vibrate_waveform（preset 或 timings+amplitudes）。指定录音源/采样率/声道用 audio_record_ex（wav 会返回 peakDbfs/rmsDbfs，可据此确认真的录到声音）；切换扬声器/听筒/蓝牙用 audio_route（只影响通话/通信路由，媒体走向由系统按已连接设备自动选择）。",
  "蓝牙扩展：让手机当 BLE 外设广播（信标/配对引导）用 ble_advertise，先看 advertiserAvailable（multipleAdvertisementsSupported=false 只代表不能并发多广播），不支持就如实说；广播名跟随本机蓝牙名，载荷超 31 字节会被拒。切媒体输出到指定耳机/音箱用 bt_audio_route（media 走 A2DP active device，属隐藏 API，NOT_APPLIED 时不要谎称已切换，引导用户去系统蓝牙页）；只要通话路由用 mode=communication。电量用 bt_battery，只读标准 GATT 0x180F，经典音频设备通常读不到，别编数字。插了什么 USB 设备用 usb_list（看 kinds 识别 MTP/ADB/MIDI/UVC）；电子乐器收发用 usb_midi。",
  "相机：先 camera_info 查能力（变焦范围、最近对焦、物理镜头角色 ultrawide/tele/macro、深度与 RAW 尺寸、厂商扩展列表）。手动 ISO/快门/曝光补偿/对焦用 camera_capture 的 iso/exposure_ns/ev/focus/focus_distance_cm；缺 MANUAL_SENSOR 时工具会返回 warnings 并降级，不要声称已手动控制。超广角/长焦/微距用 lens，变焦用 zoom（有 CONTROL_ZOOM_RATIO 走光学变焦比，否则中心裁切数码变焦）。RAW 用 camera_raw（DNG，需 RAW 能力）；深度图用 camera_depth（需 DEPTH_OUTPUT，可导 PLY 点云）；连续观察/实时 CV 用 camera_stream（亮度均值、帧间运动、条码、抽帧）；夜景/HDR/人像虚化先 camera_extension extension=info 看支持项，再用同一 extension 拍摄。能力缺失时如实告知，不要编造。",
  "蜂窝信号：问「信号为什么差/几格/什么频段/是不是 5G」先 cell_info 读主服与邻区（RSRP/RSRQ/RSSNR、SS-RSRP/SS-SINR、PCI/EARFCN、LTE 真实带宽、quality.overall 评级），用数值解释强弱（-80~-90 良好、-100 以下偏弱）；定位权限被拒或定位开关关闭会导致空列表，如实说明。cell_scan 只在用户明确要求主动扫网时调用——它需要 MODIFY_PHONE_STATE 签名权限，普通应用通常返回 ok=false/permission_denied，此时只报告缓存邻区，绝不能声称「已扫描到新小区」。问 5G 用 nr_bandwidth：NR 频段（n41/n78…）、NR-ARFCN→中心频率、FR1/FR2、SA/NSA；bandwidthEstimateMhz 是频段上限估算而不是实测占用带宽，必须说明这一点，只有 LTE 的 bandwidthKhz 是真实值。",
  "流量配额：data_policy 给 Data Saver 状态、周期移动流量、Top 应用与配额预测。NetworkPolicyManager 属系统接口，普通应用通常 access=denied，不要编造运营商配额数字；用户报了套餐额度（如 30GB）就传 limit_mb 让工具算剩余量与耗尽天数，再据此建议是否关掉大流量应用或改用 Wi-Fi。缺「使用情况访问」权限时引导用户去 设置 → 特殊应用权限 → 使用情况访问 授权。",
  "eSIM 管理：换卡/换套餐先 esim_status 看能力与在档 profile（euiccEnabled=false 多为国行阉割 eSIM 或缺 LPA，如实说明别硬试）。下载用 esim_download（activation_code 传运营商二维码里的 LPA:1$smdp$匹配ID，整段原文传入，可选 switch_after）；启用/停用/删除分别用 esim_enable/esim_disable/esim_delete，删除必须 confirm=true 且不可逆。这三类写操作要求 WRITE_EMBEDDED_SUBSCRIPTIONS（签名权限）或该 profile 的运营商特权，普通应用常被拒——返回 reason=permission_denied 时如实说明，并引导走运营商官方 App 或 设置→SIM 卡 手动激活，绝不能声称已下载/已切换/已删除。resultCode=1（resolvable_errors）表示系统 LPA 还有确认项要用户在系统界面点，本工具无法代点。",
  "音频效果链：audio_effect 用 MediaPlayer 播放本地音频并把 eq/bass/reverb/virtualizer 挂在本次 audio session 上；play 立即返回但音频在后台继续，需要调参就 action=set，结束务必 action=stop（否则会一直播放）。eq_preset 用设备预设名，逐频段用 eq_bands=60:4,230:-2；reverb_preset 默认 mediumroom。是否真的生效看 effects[].enabled/hasControl，不能只看请求参数。audio_focus 管理抢占/让出：播放默认申请 gain，被通话或其他播放器抢占会收到 loss/loss_transient（自动暂停或压低音量），status 的 events 是证据；只在用户明确要求时手动 request/abandon。spatial_audio 只在 Android 12+ 存在，RESTRICTED 或 available=false 时如实说明，不要谎称已开启空间音频。audio_record_ex 的 source=unprocessed 才是原始源（关闭 AGC/降噪/均衡），用返回的 rawSource/sourceFallback 判断是否真拿到原始数据；fallback=true 时设备不支持会退回 voice_recognition。",
].join("\n");

const HARDWARE_GUIDE_EN = [
  "Infrared remote: check ir_available first. The built-in table (Flipper-IRDB, CC0) covers LG, Samsung, Sony, Toshiba, Hitachi, Sanyo, Hisense, TCL and Haier TVs, Midea air conditioners and Wanbo projectors with common keys (power, volume, channel, navigation, OK, input source, digits); Chinese and English brand/command names both work — look up with ir_code_search, fire with ir_code_send; pronto values in the results also work with ir_send_pronto. For missing devices, fill on demand: a) find Pronto Hex 0000 online and persist it with ir_code_save; b) for a whole brand, web_read an open-source code table such as irdb (github.com/probonopd/irdb, codes/<brand>/<device>/<device>,<subdevice>.csv) and hand the CSV text to ir_code_import (brand required, device defaults to tv) — only NEC1/NEC2/NECx1/NECx2/Sony12/15/20/RC5 rows are accepted, others are skipped and reported; respect each source's license; c) custom protocols go through ir_send_raw with on/off microsecond patterns. If a device reacts twice per press, set frames=1 on ir_code_send. Stateful air conditioners need a full frame encoding temperature, mode and fan speed — save the whole frame with ir_code_save. If the phone lacks an IR emitter, report that accurately.",
  "AirPods: use airpods_scan to discover nearby broadcasts; airpods_status / airpods_battery for recent status and charge.",
  "airpods_monitor_start enables background monitoring with a notification stop action; airpods_monitor_stop stops it. airpods_history supplies battery and in-ear history for a ```chart trend.",
  "Monitor data is available in KV at airpods/latest and airpods/history. A ```live loop can read andagent.kv.get('airpods','latest'). On an in-ear true-to-false transition, call media_send_key(keycode=pause); use notify_send for low charge. Save prior state across ticks to avoid repeats.",
  "System events: media sessions and notifications require notification access via notif_permission before media_events_on / notif_events_on. Audio devices use audio_events_on. Poll latest or history in media_events, notif_events and audio_events KV namespaces from live, deduplicating by timestamp. Use the matching off tool or foreground notification to stop. Audio callbacks report device additions/removals, not guaranteed route changes.",
  "Sensors and body state: sensor_stream for vibration/attitude data (default 200Hz, multiple sensors in parallel, CSV plus per-axis stats and a vibration verdict); sensor_batch for low-power long captures (read hardwareFifo and avgEventsPerBurst to see whether batching really applies); sensor_read for a single instantaneous value. heart_rate reads TYPE_HEART_RATE — most phones lack it, so report missing hardware or NO_DATA honestly instead of inventing numbers. Use significant_motion for 'tell me when someone moves' (single hardware trigger, up to 300s). thermal_status for heat/throttling, battery_electrical for current, voltage and charge power. Haptics: vibrate_waveform with a preset or timings+amplitudes. For a specific audio source/rate/channels use audio_record_ex (wav returns peakDbfs/rmsDbfs so you can verify audio was captured); switch speaker/earpiece/Bluetooth with audio_route (communication routing only — media follows the system-selected device).",
  "Bluetooth extension: to use the phone as a BLE peripheral (beacon or pairing helper) call ble_advertise; check advertiserAvailable first (multipleAdvertisementsSupported=false only means no concurrent advertisers) and say so honestly when unsupported. The advertised name follows the phone's Bluetooth name, and payloads above the 31-byte legacy budget are rejected. Use bt_audio_route to switch media output to a specific headset/speaker (media uses the A2DP active device, a hidden API — when it returns NOT_APPLIED never claim success, point the user to system Bluetooth settings); mode=communication only changes the call route. bt_battery reads the standard GATT 0x180F service only, so classic audio devices often report nothing — never invent a level. usb_list reports what is plugged in (its kinds field names MTP/ADB/MIDI/UVC); usb_midi sends and listens to instrument traffic.",
  "Cameras: call camera_info first (zoom range, minimum focus distance, physical lens roles ultrawide/tele/macro, depth and RAW sizes, OEM extension list). Manual ISO/shutter/EV/focus use camera_capture with iso/exposure_ns/ev/focus/focus_distance_cm; when MANUAL_SENSOR is missing the tool returns warnings and falls back — never claim manual control happened. Pick lenses with lens and zoom with zoom (optical zoom ratio when CONTROL_ZOOM_RATIO exists, otherwise a digital centre crop). RAW/DNG uses camera_raw (RAW capability required); depth maps use camera_depth (DEPTH_OUTPUT required, optional PLY point cloud); continuous observation / real-time CV uses camera_stream (luma mean, inter-frame motion, barcode, frame dumps); night/HDR/bokeh start with camera_extension extension=info before shooting. Report missing capabilities honestly instead of inventing results.",
  "Cellular signal: for 'why is my signal bad / how many bars / which band / is it 5G' start with cell_info (serving and neighbour cells with RSRP/RSRQ/RSSNR, SS-RSRP/SS-SINR, PCI/EARFCN, real LTE bandwidth and quality.overall). Explain strength with the numbers (-80 to -90 good, below -100 weak); a denied location permission or a disabled location switch yields an empty list, so say so. Call cell_scan only when the user explicitly asks for a fresh over-the-air scan — it needs the MODIFY_PHONE_STATE signature permission, so third-party apps usually get ok=false/permission_denied; report the cached cells and never claim new cells were scanned. For 5G use nr_bandwidth (NR bands n41/n78…, NR-ARFCN to centre frequency, FR1/FR2, SA/NSA); bandwidthEstimateMhz is a 3GPP band upper bound, not the measured channel width, and must be described that way — only LTE bandwidthKhz is a real value.",
  "Data quota: data_policy reports Data Saver status, the mobile window usage, top apps and quota projections. NetworkPolicyManager is a system interface, so access is usually denied — never invent carrier quota numbers. When the user states a plan size (e.g. 30GB), pass limit_mb so the tool can compute remaining data and days until exhaustion, then advise on heavy apps or Wi-Fi. Without usage access, guide the user to Settings → Special app access → Usage access.",
  "eSIM management: before any SIM swap, call esim_status for capability and the on-device profile list (euiccEnabled=false usually means the device ships without eSIM/LPA — common on China-market ROMs; say so instead of forcing the attempt). Download with esim_download (activation_code takes the full LPA:1$smdp$matchingId payload from the carrier QR code; optional switch_after). Enable/disable/delete use esim_enable / esim_disable / esim_delete, and deletion requires confirm=true and is irreversible. These write operations require WRITE_EMBEDDED_SUBSCRIPTIONS (signature permission) or carrier privileges on the target profile, so third-party callers are usually denied — when reason=permission_denied comes back, report it honestly and point the user to the carrier app or Settings → SIM cards; never claim a profile was downloaded, switched or deleted. resultCode=1 (resolvable_errors) means the system LPA still has a consent step the user must complete in its own UI, which this tool cannot do for them.",
  "Audio effect chain: audio_effect plays a local file with MediaPlayer and attaches eq/bass/reverb/virtualizer to that audio session. play returns immediately while audio keeps playing in the background, so use action=set to adjust live and always action=stop when done (otherwise playback continues). eq_preset uses device preset names; per-band shaping uses eq_bands=60:4,230:-2; reverb_preset defaults to mediumroom. Check effects[].enabled/hasControl for what actually took effect instead of trusting the request. audio_focus manages preemption: playback requests gain by default, and loss/loss_transient callbacks (auto-pause or ducking) are recorded in status.events; only call request/abandon manually when the user asks. spatial_audio exists on Android 12+ only; when it returns RESTRICTED or available=false, say so instead of claiming spatial audio is on. For audio_record_ex, only source=unprocessed yields the raw source (AGC/noise suppression/EQ bypassed); use rawSource/sourceFallback to verify, and note that fallback=true silently falls back to voice_recognition on unsupported devices.",
].join("\n");

const SHORTCUT_GUIDE = [
  "桌面一键任务：用户说‘帮我做个一键打卡的桌面图标’等需求时，调用 shortcut_create，提供显示名和点击后自动发送的完整 prompt，可选单个 emoji 图标。启动器会要求用户确认 pin。",
  "小组件 widget_generate 是桌面常驻信息卡；快捷方式 shortcut_create 是点击后执行预置任务。列出已有图标用 shortcut_list；shortcut_delete 只能停用，彻底移除需用户长按桌面图标拖到卸载。",
  "下拉快捷栏磁贴：tile_set 配置显示名与点击自动发送的 prompt（一键触发常用任务）；未配置时点击磁贴只打开主界面；tile_get 查询、tile_clear 清除。",
].join("\n");

const SHORTCUT_GUIDE_EN = [
  "Desktop one-tap tasks: when the user asks for a desktop icon that runs a task, call shortcut_create with a label and the full prompt to send automatically on tap; an emoji icon is optional. The launcher asks the user to confirm pinning.",
  "widget_generate creates a persistent desktop information card; shortcut_create creates a one-tap task. Use shortcut_list to find IDs. shortcut_delete only disables a pinned icon; the user must long-press and drag it to Remove to remove it completely.",
  "Quick-settings tile: tile_set configures the tile label and the prompt sent automatically on tap (one-tap task trigger); when unconfigured, tapping opens the main screen; tile_get to query, tile_clear to reset.",
].join("\n");

const STORAGE_MEDIA_GUIDE = [
  "文本转音频文件用 tts_to_file；返回 path 后，用 ```audio 围栏展示，内容为 JSON：{\"uri\":\"返回的 path\",\"title\":\"音频\"}。用户可点播放器播放；文件保存在应用 exports 目录。",
  "语音输入用 speech_recognize：用户说话（默认 zh-CN，可用 language 指定）→ 返回 text/confidence → 再按需求处理；它与 tts_to_file 构成「听/说」闭环。设备无系统识别服务时如实报错，不要用 mic_record 假装转写。",
  "图片文字识别用 ocr_text（离线 OCR，中英混排均可）：用户发来截图/照片/截图路径，或要求「把图里的字读出来/转成文本」时，把图片路径或 uri 作为 source 调用；返回 status/text/lines（含坐标）。status=NO_TEXT 表示图中没有可识别文字，如实告知即可。看不到像素不要猜内容——一切以 ocr_text 返回为准。",
  "结构化存储：用户要记账、清单或笔记库时，先用 db_create 建库，再用 db_exec 建表，后续用参数化 INSERT 和 db_query SELECT。",
  "轻量键值：用户要记住偏好、计数器或小型状态时，用 kv_set 写入，kv_get/kv_keys 读取；临时数据加 ttl_ms；清空命名空间用 kv_clear 且 confirm=true；备份/恢复用 kv_export/kv_import（replace 需 confirm=true）（最多 64 个命名空间、每命名空间 512 键、单值 64KB）。",
  "记账表例：CREATE TABLE records(id INTEGER PRIMARY KEY, date TEXT, item TEXT, amount REAL)。",
  "输出呈现：给用户音频或视频文件时，用 ```audio 或 ```video 围栏，内容为 JSON，例如 {\"uri\":\"/本地路径/file.mp4\",\"title\":\"文件名\"}；uri 可为本地文件路径或 https。",
].join("\n");

const STORAGE_MEDIA_GUIDE_EN = [
  "Use tts_to_file to create an audio file from text. After it returns path, show a ```audio fence containing JSON {\"uri\":\"returned path\",\"title\":\"Audio\"}. The user can play it in the card; the file is saved in the app exports directory.",
  "Use speech_recognize for voice input: the user speaks (default zh-CN; set language for other locales) and you get text/confidence to act on. Together with tts_to_file it closes the listen/speak loop. If the device has no recognition service, report the error instead of pretending mic_record transcribed audio.",
  "For text in images (OCR) use ocr_text (offline, handles mixed Chinese/Latin): pass the image path or uri as source — e.g. the path returned by screen_capture or camera_capture. It returns status/text/lines (with boxes). status=NO_TEXT means no readable text; say so honestly. You cannot see pixels — never guess image content, trust the ocr_text result.",
  "Structured storage: for expenses, lists, or notes, create a database with db_create, then a table with db_exec; use parameterized INSERT and db_query SELECT afterward.",
  "Key-value storage: use kv_set for preferences, counters, or small state; kv_get/kv_keys to read. Use ttl_ms for temporary values. kv_clear requires confirm=true; kv_export/kv_import handle JSON backups (replace requires confirm=true) (up to 64 namespaces, 512 keys per namespace, 64KB per value).",
  "Example expense schema: CREATE TABLE records(id INTEGER PRIMARY KEY, date TEXT, item TEXT, amount REAL).",
  "For audio or video files, output a ```audio or ```video fence with JSON such as {\"uri\":\"/local/path/file.mp4\",\"title\":\"Filename\"}. uri may be a local path or HTTPS URL.",
].join("\n");

/** Always appended, so prompts saved before this fix cannot re-enable base64 fences. */
const MEDIA_CONTEXT_GUIDE = [
  "图片与上下文约定：",
  "- screen_capture、album_photos、image_edit、image_preview、image_collage 的缩略图由工具卡片自动渲染给用户；工具结果里的图片数据不会进入你的上下文，你也看不到像素。",
  "- 不要输出 ```image / ```photos 围栏，不要复述或粘贴 base64、previewFence 等图片数据；用文字说明结果、文件数量、savedPath 或 content:// uri。",
  "- 需要继续处理图片时，用返回的路径或 uri 作为下一次工具调用的 source。",
].join("\n");

const MEDIA_CONTEXT_GUIDE_EN = [
  "Image and context rules:",
  "- Tool cards automatically display thumbnails from screen_capture, album_photos, image_edit, image_preview, and image_collage. Image bytes do not enter your context, so you cannot see the pixels.",
  "- Do not output ```image / ```photos fences or repeat base64 or previewFence. Describe results, counts, savedPath, or content:// uri in text.",
  "- For further image processing, pass the returned path or uri as the next tool source.",
].join("\n");

const A2UI_GUIDE = [
  "结构化表单卡（```a2ui 围栏）：",
  "1. 需要用户多选、填写参数或确认选项时，输出 ```a2ui 围栏（合法 JSON）代替纯文字追问。",
  '2. 顶层格式：{"title":"标题","submit_label":"提交","fields":[...]}；fields 必须是数组且非空。',
  "3. 只能使用以下 catalog 组件：TEXT_INPUT（key,label,placeholder,prefill,required,max_length,validate 取 email|phone|regex,pattern）、NUMBER_SLIDER（min,max,step,default,prefill）、CHOICE（options 为字符串数组或 {value,label} 数组，multiple 可选，prefill）、DATE_PICK（prefill）、SWITCH（default,prefill）、BUTTON_GROUP（buttons）、CARD（title,text）、ROW/COLUMN（children 递归嵌套）、LABEL（text）。",
  "4. 每个字段 key 必须唯一；总节点数不超过 60；只适合 2-8 个字段的轻量表单，不要生成超大表单；ROW/COLUMN 最多嵌套 8 层。",
  "5. prefill 用作字段初始值；生成带预填的表单、或由 cron 定时任务唤起确认表单时，直接把 prefill 写进 JSON：TEXT_INPUT/DATE_PICK/单选 CHOICE 用 string，NUMBER_SLIDER 用 number，SWITCH 用 boolean，多选 CHOICE 用 string 数组；类型不匹配的 prefill 会被忽略。用户可照常修改，提交值以最终填写为准。",
  '6. 用户提交后，卡片会把表单值以 {"a2ui_submit":{...},"a2ui_title":"标题"} 回填到输入框；用户确认发送后你才会收到。收到后直接按其中的字段继续任务，不要重复追问。',
  "7. cron 定时任务可配 kind=\"a2ui\"：cron_create 的 payload 直接存完整 a2ui JSON spec，触发时表单渲染进对话（锁屏/后台先降级通知，解锁后补渲染）。适合晨报确认（今日日程勾选+天气开关）、备份范围单（CHOICE 多选目录）、删除确认单（BUTTON_GROUP+风险 CARD）三个场景。",
  "8. 示例：",
  "```a2ui",
  '{"title":"导出设置","submit_label":"提交","fields":[{"type":"TEXT_INPUT","key":"email","label":"接收邮箱","placeholder":"you@example.com","prefill":"me@example.com","required":true,"validate":"email"},{"type":"CHOICE","key":"format","label":"导出格式","options":["Markdown","PDF"],"prefill":"PDF"},{"type":"SWITCH","key":"include_images","label":"包含图片","default":true,"prefill":true}]}',
  "```",
  "9. cron a2ui 模板三例（kind=\"a2ui\" 时把整段 JSON 原样作 cron_create 的 payload；prefill 随触发日填入）：",
  "· 晨报（五组件齐全 + 多选 CHOICE 预填）：",
  "```a2ui",
  '{"title":"晨报确认","submit_label":"提交晨报","fields":[{"type":"DATE_PICK","key":"date","label":"日期","prefill":"2026-11-08"},{"type":"TEXT_INPUT","key":"weather","label":"天气","prefill":"晴 26℃"},{"type":"CHOICE","key":"agenda","label":"今日日程","multiple":true,"options":["站会","评审","复盘"],"prefill":["站会","复盘"]},{"type":"SWITCH","key":"todo","label":"看待办","default":false,"prefill":true},{"type":"NUMBER_SLIDER","key":"focus","label":"专注分钟","min":0,"max":60,"step":5,"default":25,"prefill":35}]}',
  "```",
  "· 备份范围（多选目录 + 开关）：",
  "```a2ui",
  '{"title":"备份范围","submit_label":"开始备份","fields":[{"type":"CHOICE","key":"folders","label":"要备份的目录","multiple":true,"options":["DCIM","Download","Documents","WhatsApp"],"prefill":["DCIM","Download"]},{"type":"SWITCH","key":"include_hidden","label":"包含隐藏文件","default":false,"prefill":false},{"type":"TEXT_INPUT","key":"note","label":"备注","prefill":"每周例行"}]}',
  "```",
  "· 删除确认（BUTTON_GROUP 必选 + 风险 CARD，不预填确认项）：",
  "```a2ui",
  '{"title":"删除确认","submit_label":"提交","fields":[{"type":"CARD","title":"高风险操作","text":"将永久删除所选数据，不可恢复。"},{"type":"BUTTON_GROUP","key":"confirm","label":"是否继续","required":true,"buttons":[{"value":"cancel","label":"取消"},{"value":"delete","label":"确认删除"}]},{"type":"TEXT_INPUT","key":"reason","label":"删除原因","prefill":"清理旧备份"}]}',
  "```",
].join("\n");

const A2UI_GUIDE_EN = [
  "Structured form cards (```a2ui fence):",
  "1. When the user must pick options, fill in parameters, or confirm choices, output an ```a2ui fence with valid JSON instead of asking in plain text.",
  '2. Top-level shape: {"title":"Title","submit_label":"Submit","fields":[...]}; fields must be a non-empty array.',
  "3. Use only these catalog components: TEXT_INPUT (key,label,placeholder,prefill,required,max_length,validate as email|phone|regex,pattern), NUMBER_SLIDER (min,max,step,default,prefill), CHOICE (options as a string array or {value,label} array, optional multiple, prefill), DATE_PICK (prefill), SWITCH (default,prefill), BUTTON_GROUP (buttons), CARD (title,text), ROW/COLUMN (nested children), LABEL (text).",
  "4. Every field key must be unique; keep the total node count at or below 60; use this only for lightweight forms of 2-8 fields, never huge forms; ROW/COLUMN nesting is limited to 8 levels.",
  "5. prefill sets a field's initial value; write it directly into the JSON when emitting a pre-filled form or a cron-triggered confirmation form: a string for TEXT_INPUT/DATE_PICK/single CHOICE, a number for NUMBER_SLIDER, a boolean for SWITCH, and a string array for multiple CHOICE. A prefill with a mismatched type is ignored. The user can still edit it; the submitted value is whatever they finally leave.",
  '6. After the user submits, the card fills {"a2ui_submit":{...},"a2ui_title":"Title"} into the composer; you receive it only after the user presses send. Then continue the task using those fields and do not ask again.',
  "7. Cron tasks support kind=\"a2ui\": store the full a2ui JSON spec as cron_create's payload; at trigger time the form renders into the conversation (locked/background degrades to a notification, the form re-renders on unlock). Good fits: morning briefing (today's agenda checkboxes + weather switch), backup scope form (multi-select CHOICE of folders), delete confirmation (BUTTON_GROUP + risk CARD).",
  "8. Example:",
  "```a2ui",
  '{"title":"Export settings","submit_label":"Submit","fields":[{"type":"TEXT_INPUT","key":"email","label":"Email","placeholder":"you@example.com","prefill":"me@example.com","required":true,"validate":"email"},{"type":"CHOICE","key":"format","label":"Format","options":["Markdown","PDF"],"prefill":"PDF"},{"type":"SWITCH","key":"include_images","label":"Include images","default":true,"prefill":true}]}',
  "```",
  "9. Three cron a2ui templates (use the JSON verbatim as cron_create's payload when kind=\"a2ui\"; fill prefill for the trigger day):",
  "· Morning briefing (all five components + multi-select CHOICE prefill):",
  "```a2ui",
  '{"title":"Morning briefing","submit_label":"Submit","fields":[{"type":"DATE_PICK","key":"date","label":"Date","prefill":"2026-11-08"},{"type":"TEXT_INPUT","key":"weather","label":"Weather","prefill":"Sunny 26C"},{"type":"CHOICE","key":"agenda","label":"Agenda","multiple":true,"options":["Standup","Review","Retro"],"prefill":["Standup","Retro"]},{"type":"SWITCH","key":"todo","label":"Show todos","default":false,"prefill":true},{"type":"NUMBER_SLIDER","key":"focus","label":"Focus minutes","min":0,"max":60,"step":5,"default":25,"prefill":35}]}',
  "```",
  "· Backup scope (multi-select folders + switch):",
  "```a2ui",
  '{"title":"Backup scope","submit_label":"Start backup","fields":[{"type":"CHOICE","key":"folders","label":"Folders","multiple":true,"options":["DCIM","Download","Documents","WhatsApp"],"prefill":["DCIM","Download"]},{"type":"SWITCH","key":"include_hidden","label":"Include hidden files","default":false,"prefill":false},{"type":"TEXT_INPUT","key":"note","label":"Note","prefill":"Weekly routine"}]}',
  "```",
  "· Delete confirmation (required BUTTON_GROUP + risk CARD, no prefill on the confirm choice):",
  "```a2ui",
  '{"title":"Delete confirmation","submit_label":"Submit","fields":[{"type":"CARD","title":"High-risk action","text":"Selected data will be permanently deleted and cannot be recovered."},{"type":"BUTTON_GROUP","key":"confirm","label":"Proceed?","required":true,"buttons":[{"value":"cancel","label":"Cancel"},{"value":"delete","label":"Delete"}]},{"type":"TEXT_INPUT","key":"reason","label":"Reason","prefill":"Clean old backups"}]}',
  "```",
].join("\n");

const ZERO_USAGE = {
  input: 0,
  output: 0,
  cacheRead: 0,
  cacheWrite: 0,
  totalTokens: 0,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
};

function buildModel(settings: Settings): Model<"openai-completions"> {
  return {
    id: settings.model,
    name: settings.model,
    api: "openai-completions",
    provider: "custom",
    baseUrl: settings.baseUrl.trim().replace(/\/+$/, ""),
    // R88: 视觉模型才声明 image；非视觉模型仍可尝试发送，由服务端如实报错。
    input: visionCapable(settings.model) ? ["text", "image"] : ["text"],
    // GLM 流式回 reasoning_content，pi 自动转 thinking part → UI 折叠块
    reasoning: true,
    contextWindow: 128_000,
    maxTokens: 8_192,
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    // GLM (and most OpenAI-compatible endpoints) wants max_tokens, not
    // max_completion_tokens; the system role, not developer.
    compat: {
      maxTokensField: "max_tokens",
      supportsStore: false,
      supportsDeveloperRole: false,
      supportsUsageInStreaming: true,
    },
  };
}

export interface ToolView {
  id: string;
  name: string;
  args: Record<string, any>;
  result?: string;
  isError?: boolean;
  running?: boolean;
}

type Draft = { text: string; thinking: string; tools: ToolView[] };
type UiPart = Exclude<NonNullable<ThreadMessageLike["content"]>, string>[number];

export interface LastUsage {
  input: number;
  output: number;
  durationMs?: number;
  aborted: boolean;
}

export interface AgentSnapshot {
  messages: ThreadMessageLike[];
  running: boolean;
  error: string | null;
  sessionId: string;
  sessions: SessionMeta[];
  usage: { session: UsageSum; last: LastUsage | null };
}

/** UI 层用户消息 id：`u-<消息下标>-<时间戳>`（下标指向完整的 PiMessage 数组）。 */
function userUiId(index: number, timestamp: number): string {
  return `u-${index}-${timestamp}`;
}

/** 从 UI 消息 id 反解出完整消息数组里的下标；不是用户消息 id 时返回 -1。 */
export function userMessageIndexFromUiId(uiId: string): number {
  const match = /^u-(\d+)-\d+$/.exec(uiId);
  return match ? Number(match[1]) : -1;
}

/**
 * R88: 用户消息 content。带图时是 pi-ai 的 content 数组，但图片 part 只带
 * `path`（base64 由 [hydrateChatImages] 在发请求前补水），这样会话 KV 里
 * 只存几十字节的相对路径，而不是几百 KB 的 base64。
 */
function userMessageContent(text: string, images: ChatImage[]): UserMessage["content"] {
  if (images.length === 0) return text;
  const parts: Array<Record<string, unknown>> = [];
  if (text) parts.push({ type: "text", text });
  for (const image of images) {
    parts.push({ type: "image", path: image.path, mimeType: image.mimeType || "image/jpeg" });
  }
  return parts as unknown as UserMessage["content"];
}

/** 供测试直接校验快照状态映射。 */
export function toUiMessages(messages: PiMessage[], draft: Draft | null): ThreadMessageLike[] {
  const results = new Map<string, { text: string; isError: boolean }>();
  for (const message of messages) {
    if (message.role === "toolResult") {
      const text = message.content
        .map((part) => (part.type === "text" ? part.text : "[image]"))
        .join("\n");
      results.set(message.toolCallId, { text, isError: message.isError });
    }
  }

  const ui: ThreadMessageLike[] = [];
  messages.forEach((message, index) => {
    if (message.role === "user") {
      // R88: 图片附件是消息 content 里的 image part（只存相对路径），
      // 渲染走 /local/ 只读路由，气泡里按缩略图铺开。
      const parts: UiPart[] = [];
      if (typeof message.content === "string") {
        parts.push({ type: "text", text: message.content });
      } else {
        for (const part of message.content) {
          if (part.type === "text") {
            if (part.text) parts.push({ type: "text", text: part.text });
          } else if (part.type === "image") {
            const path = (part as { path?: string }).path;
            if (path) {
              parts.push({ type: "image", image: chatImageUrl(path) } as UiPart);
            }
          }
        }
      }
      // R82: 「已编辑」标记是挂在消息对象上的 sidecar，随会话一并持久化。
      const edited = (message as { edited?: boolean }).edited === true;
      ui.push({
        id: userUiId(index, message.timestamp),
        role: "user",
        content: parts.length ? parts : [{ type: "text", text: "" }],
        ...(edited ? { metadata: { custom: { edited: true } } } : {}),
        createdAt: new Date(message.timestamp),
      });
      return;
    }
    if (message.role !== "assistant") return; // toolResult is folded into the call card

    const parts: UiPart[] = [];
    for (const part of message.content) {
      if (part.type === "thinking") {
        if (part.thinking) parts.push({ type: "reasoning", text: part.thinking } as UiPart);
      } else if (part.type === "text") {
        if (part.text) parts.push({ type: "text", text: part.text });
      } else if (part.type === "toolCall") {
        const result = results.get(part.id);
        parts.push({
          type: "tool-call",
          toolCallId: part.id,
          toolName: part.name,
          args: part.arguments ?? {},
          argsText: JSON.stringify(part.arguments ?? {}),
          result: result?.text,
          isError: result?.isError ?? false,
        });
      }
    }
    const incomplete =
      message.stopReason === "aborted" || message.stopReason === "error";
    // R40: usage 走 metadata.custom 透传给消息组件；durationMs 是 AgentEngine
    // 挂在消息对象上的 sidecar（随会话一并持久化）。
    const custom: Record<string, unknown> = {
      usage: { input: message.usage?.input ?? 0, output: message.usage?.output ?? 0 },
      aborted: message.stopReason === "aborted",
    };
    const durationMs = (message as { durationMs?: number }).durationMs;
    if (durationMs !== undefined) custom.durationMs = durationMs;
    // 完成态不显式给 status：让 assistant-ui 按「最后一条 + 线程 running」自动推断，
    // 否则写死 complete 会压住自动状态，工具执行完等下一段回复时就没有等待指示。
    ui.push({
      id: `a-${index}-${message.timestamp}`,
      role: "assistant",
      content: parts.length ? parts : [{ type: "text", text: "" }],
      ...(incomplete
        ? {
            status: {
              type: "incomplete" as const,
              reason:
                message.stopReason === "aborted" ? ("cancelled" as const) : ("error" as const),
            },
          }
        : {}),
      metadata: { custom },
      createdAt: new Date(message.timestamp),
    });
  });

  if (draft) {
    const parts: UiPart[] = [];
    if (draft.thinking) {
      // Native reasoning part — assistant-ui renders it via ReasoningUI.
      parts.push({ type: "reasoning", text: draft.thinking } as UiPart);
    }
    if (draft.text) parts.push({ type: "text", text: draft.text });
    for (const tool of draft.tools) {
      parts.push({
        type: "tool-call",
        toolCallId: tool.id,
        toolName: tool.name,
        args: tool.args,
        argsText: JSON.stringify(tool.args ?? {}),
        result: tool.result,
        isError: tool.isError ?? false,
        isPreliminary: tool.running || tool.result === undefined,
      });
    }
    ui.push({
      id: "live",
      role: "assistant",
      content: parts.length ? parts : [{ type: "text", text: "" }],
      status: { type: "running" },
      createdAt: new Date(),
    });
  }
  return ui;
}

function newId(): string {
  return `s_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`;
}

function isAbortError(error: unknown): boolean {
  if (!error) return false;
  const name = (error as { name?: string }).name;
  const message = String((error as { message?: string }).message ?? error);
  return name === "AbortError" || /abort|cancel/i.test(message);
}

/** Usage of the last assistant message in the transcript (null if none). */
function lastUsage(messages: PiMessage[]): LastUsage | null {
  for (let i = messages.length - 1; i >= 0; i--) {
    const message = messages[i];
    if (message.role !== "assistant") continue;
    const durationMs = (message as { durationMs?: number }).durationMs;
    return {
      input: message.usage?.input ?? 0,
      output: message.usage?.output ?? 0,
      ...(durationMs !== undefined ? { durationMs } : {}),
      aborted: message.stopReason === "aborted",
    };
  }
  return null;
}

function friendlyError(error: unknown): string {
  const raw = String((error as { message?: string })?.message ?? error);
  if (/Failed to fetch|NetworkError|Load failed/i.test(raw)) {
    return `网络请求失败：${raw}。请检查 baseUrl 与网络连接。`;
  }
  return raw.length > 600 ? raw.slice(0, 600) + "…" : raw;
}

export class AgentEngine {
  private messages: PiMessage[];
  private draft: Draft | null = null;
  private running = false;
  private error: string | null = null;
  private abort: AbortController | null = null;
  private settings: Settings;
  private snapshot: AgentSnapshot;
  private listeners = new Set<() => void>();
  private sessionId = "";
  private sessions: SessionMeta[] = [];
  /** R40: 会话级 token 累计（仅当前会话；切换/新建时重算或清零）。 */
  private sessionUsage: UsageSum = emptyUsage();

  constructor(settings: Settings) {
    this.settings = settings;
    this.sessionId = newId();
    this.messages = [];
    this.sessions = SessionStore.list();
    this.snapshot = {
      messages: [],
      running: false,
      error: null,
      sessionId: this.sessionId,
      sessions: this.sessions,
      usage: { session: { ...this.sessionUsage }, last: null },
    };
  }

  /** R105: whether an LLM turn is in flight. App.tsx gates the durable cron
   * a2ui inbox drain on this so a form is never consumed-and-dropped mid-turn. */
  get isRunning(): boolean {
    return this.running;
  }

  /* ------------------------- multi-session --------------------------- */

  switchSession(id: string): void {
    if (this.running || id === this.sessionId) return;
    SessionStore.save(this.sessionId, this.messages); // flush current first
    this.sessionId = id;
    this.messages = SessionStore.load(id);
    this.draft = null;
    this.error = null;
    this.sessionUsage = sumMessageUsage(this.messages);
    this.sessions = SessionStore.list();
    this.emit();
  }

  newSession(): void {
    if (this.running) return;
    SessionStore.save(this.sessionId, this.messages);
    this.sessionId = newId();
    this.messages = [];
    this.draft = null;
    this.error = null;
    this.sessionUsage = emptyUsage();
    this.sessions = SessionStore.list();
    this.emit();
  }

  renameSession(id: string, title: string): void {
    this.sessions = SessionStore.rename(id, title);
    this.emit();
  }

  deleteSession(id: string): void {
    const wasCurrent = id === this.sessionId;
    const sessions = SessionStore.remove(id);
    if (wasCurrent) {
      this.sessionId = newId();
      this.messages = [];
      this.draft = null;
      this.error = null;
      this.sessionUsage = emptyUsage();
    }
    this.sessions = sessions;
    this.emit();
  }

  deleteSessions(ids: string[]): void {
    if (ids.length === 0) return;
    const wasCurrent = ids.includes(this.sessionId);
    this.sessions = SessionStore.removeMany(ids);
    if (wasCurrent) {
      const next = this.sessions[0];
      this.sessionId = next?.id ?? newId();
      this.messages = next ? SessionStore.load(next.id) : [];
      this.draft = null;
      this.error = null;
      this.sessionUsage = sumMessageUsage(this.messages);
    }
    this.emit();
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getSnapshot = (): AgentSnapshot => this.snapshot;

  setSettings(settings: Settings): void {
    this.settings = settings;
  }

  clear(): void {
    this.messages = [];
    this.draft = null;
    this.error = null;
    this.sessionUsage = emptyUsage();
    this.persist();
    this.emit();
  }

  /**
   * R105: inject a cron a2ui form as a deterministic assistant message (no
   * LLM round-trip). The fence renders via Markdown -> A2uiCard; submitting
   * fills {a2ui_submit} into the composer, which the agent receives on send.
   * Never splices into a running turn.
   */
  appendA2uiForm(fence: string): void {
    if (this.running || !fence.trim()) return;
    const message: AssistantMessage = {
      role: "assistant",
      content: [{ type: "text", text: fence }],
      api: "openai-completions",
      provider: "custom",
      model: this.settings.model,
      usage: ZERO_USAGE,
      stopReason: "stop",
      timestamp: Date.now(),
    };
    this.messages = [...this.messages, message];
    this.persist();
    this.emit();
  }

  /**
   * R104: 停止必须对「等待模型」「等待工具」「等待确认」三种状态同时生效。
   * 只 abort 模型请求时，正在跑的工具（60s～30min）会让停止按钮看起来毫无作用。
   */
  stop(): void {
    const controller = this.abort;
    if (!controller) return;
    controller.abort();
    cancelAgentWork();
  }

  /* --------------------- edit & resend (R82) ------------------------- */

  /**
   * 编辑已发送的用户消息：截断这条消息及其之后的全部历史（含工具结果与
   * 助手回复），用新文本重新发起一轮。
   *
   * 截断不可逆——调用方负责先向用户确认「已执行的工具操作不会撤销」。
   * 返回 false 表示目标消息不存在或本轮无法开始。
   */
  async editAndResend(uiMessageId: string, text: string): Promise<boolean> {
    const content = text.trim();
    if (!content) return false;
    const index = userMessageIndexFromUiId(uiMessageId);
    const target = index >= 0 ? this.messages[index] : undefined;
    // R88: 改文案不丢图——原消息的图片附件继续带上。
    const images = target && target.role === "user" ? imagesOfMessage(target) : [];
    return this.resendFrom(uiMessageId, content, true, images);
  }

  /** 重试本轮：截断这条消息之后的历史，用原文（含图片附件）重发。 */
  async retryFrom(uiMessageId: string): Promise<boolean> {
    const index = userMessageIndexFromUiId(uiMessageId);
    const target = index >= 0 ? this.messages[index] : undefined;
    if (!target || target.role !== "user") return false;
    const text =
      typeof target.content === "string"
        ? target.content
        : target.content.map((part) => (part.type === "text" ? part.text : "")).join("");
    const images = imagesOfMessage(target);
    if (!text.trim() && images.length === 0) return false;
    return this.resendFrom(uiMessageId, text, false, images);
  }

  /**
   * 截断到目标用户消息并重发。editing=true 时用新文本替换原消息，
   * 否则原样重发（重试本轮），两种入口共用同一套截断/持久化逻辑。
   */
  private async resendFrom(
    uiMessageId: string,
    text: string,
    editing: boolean,
    images: ChatImage[] = [],
  ): Promise<boolean> {
    if (!(await this.waitIdle())) return false;
    const index = userMessageIndexFromUiId(uiMessageId);
    const target = index >= 0 ? this.messages[index] : undefined;
    if (!target || target.role !== "user") return false;
    // 没配置模型时不能先截断（否则历史白丢）：交给 send 只写错误提示。
    if (!isModelConfigured(this.settings)) {
      await this.send(text, { edited: editing, images });
      return false;
    }

    // 截断 → 会话 token 统计重算 → 立即落盘，避免中途退出留下半截历史。
    this.messages = this.messages.slice(0, index);
    this.sessionUsage = sumMessageUsage(this.messages);
    this.draft = null;
    this.error = null;
    this.persist();
    this.emit();

    // send 内部会追加新的用户消息；editing 时给它打上「已编辑」标记。
    await this.send(text, { edited: editing, images });
    return true;
  }

  /** 生成中先中断当前轮（复用 stop 的取消链路），等本轮 finally 收尾后再动手。 */
  private async waitIdle(timeoutMs = 5000): Promise<boolean> {
    if (!this.running) return true;
    this.stop();
    const deadline = Date.now() + timeoutMs;
    while (this.running && Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 30));
    }
    return !this.running;
  }

  private emit(): void {
    this.snapshot = {
      messages: toUiMessages(this.messages, this.draft),
      running: this.running,
      error: this.error,
      sessionId: this.sessionId,
      sessions: this.sessions,
      usage: { session: { ...this.sessionUsage }, last: lastUsage(this.messages) },
    };
    for (const listener of this.listeners) listener();
  }

  private persist(): void {
    this.sessions = SessionStore.save(this.sessionId, this.messages);
  }

  /** 记录一条工具结果；同一 toolCallId 只写一次（停止补记时幂等）。 */
  private recordToolResult(call: ToolCall, text: string, isError: boolean): void {
    if (this.messages.some((m) => m.role === "toolResult" && m.toolCallId === call.id)) return;
    const toolResult: ToolResultMessage = {
      role: "toolResult",
      toolCallId: call.id,
      toolName: call.name,
      content: [{ type: "text", text }],
      isError,
      timestamp: Date.now(),
    };
    this.messages = [...this.messages, toolResult];
    this.persist();
    this.emit();
  }

  /**
   * R104: 卡片上的「停止」兜底。工具已经不在跑时（历史会话里没落结果的
   * toolCall、原生回包丢失、本轮刚被整体停止），把卡片补记成中断态，
   * 否则卡片永远显示「执行中」，而且再点也没有任何反应。
   */
  cancelToolCall(callId: string): void {
    if (!callId) return;
    if (this.draft && this.draft.tools.some((tool) => tool.id === callId)) {
      this.draft = { ...this.draft, tools: this.draft.tools.filter((tool) => tool.id !== callId) };
      this.emit();
      return;
    }
    if (this.messages.some((m) => m.role === "toolResult" && m.toolCallId === callId)) return;
    for (const message of this.messages) {
      if (message.role !== "assistant") continue;
      const call = message.content.find(
        (part): part is ToolCall => part.type === "toolCall" && part.id === callId,
      );
      if (call) {
        this.recordToolResult(call, STOPPED_RESULT, true);
        return;
      }
    }
  }

  async send(text: string, options?: { edited?: boolean; images?: ChatImage[] }): Promise<void> {
    const content = text.trim();
    // R88: 只发图、不写字也允许（图片附件的 content 数组里没有 text part）。
    const images = (options?.images ?? []).filter((image) => !!image?.path).slice(0, MAX_CHAT_IMAGES);
    if ((!content && images.length === 0) || this.running) return;
    if (!isModelConfigured(this.settings)) {
      this.error = currentLanguage() === "zh" ? "还没有连接大模型，请点「去配置」。" : "Connect a model using Configure first.";
      this.emit();
      return;
    }

    this.running = true;
    this.error = null;
    this.draft = null;
    const controller = new AbortController();
    this.abort = controller;
    // R93 必修项：本轮绑定发起时的会话。期间用户删除/切换会话时，
    // 旧轮次在各写回点自作废，绝不把回复写进切换后的新会话。
    const turnSessionId = this.sessionId;
    const sessionChanged = (): boolean => this.sessionId !== turnSessionId;
    const userMessage: UserMessage = {
      role: "user",
      content: userMessageContent(content, images),
      timestamp: Date.now(),
    };
    // R82: 「已编辑」是挂在消息对象上的 sidecar，随会话一并持久化。
    if (options?.edited) (userMessage as { edited?: boolean }).edited = true;
    this.messages = [...this.messages, userMessage];
    this.persist();
    this.emit();

    let pendingText = "";
    // R39+: 流式标记——CodeBlock（Markdown.tsx）据此把重围栏渲染为占位防闪烁。
    const setStreamingFlag = (on: boolean) => {
    };
    setStreamingFlag(true);
    try {
      const pi = await loadPi();
      if (controller.signal.aborted) {
        this.finishAborted(pendingText);
        return;
      }
      let rounds = 0;
      let finished = false;
      while (rounds < MAX_TOOL_ROUNDS) {
        if (controller.signal.aborted) {
          this.finishAborted(pendingText);
          return;
        }
        rounds++;
        const model = buildModel(this.settings);
        const stream = pi.stream(
          model,
          {
            systemPrompt: [
              currentLanguage() === "en" ? LANGUAGE_GUIDE_EN : LANGUAGE_GUIDE,
              this.settings.systemPrompt.trim(),
              memoryPrompt(content),
              skillPrompt(),
              currentLanguage() === "en" ? "Reflection: android_reflect can call only allowlisted methods of AudioManager, MediaRouter, CameraManager/CameraCharacteristics, SensorManager, PowerManager, ActivityManager, ConnectivityManager, Build/SystemClock, Settings.System/Global/Secure, TelephonyManager, DisplayManager, and PackageManager. See declared api_* tools; prefer dedicated tools." : "反射桥：android_reflect 仅能调用 AudioManager、MediaRouter、CameraManager/CameraCharacteristics、SensorManager、PowerManager、ActivityManager、ConnectivityManager、Build/SystemClock、Settings.System/Global/Secure、TelephonyManager、DisplayManager、PackageManager 的白名单方法。批量 api_* 工具见工具列表；优先用专门工具，反射用于长尾查询。",
              currentLanguage() === "en" ? CHART_GUIDE_EN : CHART_GUIDE,
              currentLanguage() === "en" ? LIVE_GUIDE_EN : LIVE_GUIDE,
              currentLanguage() === "en" ? WEB_DEV_GUIDE_EN : WEB_DEV_GUIDE,
              currentLanguage() === "en" ? HARDWARE_GUIDE_EN : HARDWARE_GUIDE,
              currentLanguage() === "en" ? NETWORK_GUIDE_EN : NETWORK_GUIDE,
              currentLanguage() === "en" ? WIFI_GUIDE_EN : WIFI_GUIDE,
              currentLanguage() === "en" ? A11Y_GUIDE_EN : A11Y_GUIDE,
              currentLanguage() === "en" ? SSH_TRANSFER_GUIDE_EN : SSH_TRANSFER_GUIDE,
              currentLanguage() === "en" ? SHORTCUT_GUIDE_EN : SHORTCUT_GUIDE,
              currentLanguage() === "en" ? STORAGE_MEDIA_GUIDE_EN : STORAGE_MEDIA_GUIDE,
              currentLanguage() === "en" ? MEDIA_CONTEXT_GUIDE_EN : MEDIA_CONTEXT_GUIDE,
              currentLanguage() === "en" ? A2UI_GUIDE_EN : A2UI_GUIDE,
              currentLanguage() === "en" ? PERMISSION_GUIDE_EN : PERMISSION_GUIDE,
              currentLanguage() === "en" ? SELF_EXT_GUIDE_EN : SELF_EXT_GUIDE,
            ]
              .filter(Boolean)
              .join("\n\n"),
            // Compact projection: base64 tool payloads never reach the prompt.
            // R88: 图片附件的 base64 在这一步按路径补水（带缓存），消息本体只存路径。
            messages: hydrateChatImages(toModelMessages(this.messages)),
            tools: [...TOOL_DEFS, ...extensionTools()],
          },
          {
            // pi-ai's OpenAI client requires a nonempty key even when the
            // local server has no authentication. This is only a placeholder.
            apiKey: this.settings.apiKey.trim() || "andagent-local",
            baseUrl: model.baseUrl,
            signal: controller.signal,
            maxRetries: 0,
          },
        );

        let liveText = "";
        let liveThinking = "";
        let liveTools: ToolView[] = [];
        const roundStart = Date.now();
        for await (const event of stream as AsyncIterable<AssistantMessageEvent>) {
          // 停止后不再继续消费流事件（原生请求已 cancel，但已排队的行还会到达）。
          if (controller.signal.aborted) break;
          if (event.type === "text_delta") {
            liveText += event.delta;
            pendingText = liveText;
            this.draft = { text: liveText, thinking: liveThinking, tools: liveTools };
            this.emit();
          } else if (event.type === "thinking_delta") {
            liveThinking += event.delta;
            this.draft = { text: liveText, thinking: liveThinking, tools: liveTools };
            this.emit();
          } else if (event.type === "toolcall_start") {
            const partial = event.partial.content[event.contentIndex];
            if (partial?.type === "toolCall" && !liveTools.some((t) => t.id === partial.id)) {
              liveTools = [
                ...liveTools,
                { id: partial.id, name: partial.name, args: {}, running: true },
              ];
              this.draft = { text: liveText, thinking: liveThinking, tools: liveTools };
              this.emit();
            }
          } else if (event.type === "toolcall_end") {
            const call = event.toolCall;
            liveTools = liveTools.filter((t) => t.id !== call.id);
            liveTools.push({
              id: call.id,
              name: call.name,
              args: call.arguments ?? {},
              running: true,
            });
            this.draft = { text: liveText, thinking: liveThinking, tools: liveTools };
            this.emit();
          }
        }

        // 停止时不再等 result()（流可能仍挂在原生回包上），直接收尾。
        if (controller.signal.aborted) {
          this.finishAborted(pendingText);
          return;
        }
        const final = await stream.result();
        if (controller.signal.aborted || final.stopReason === "aborted") {
          this.finishAborted(pendingText);
          return;
        }
        if (final.stopReason === "error") {
          throw new Error(final.errorMessage || "模型流式返回错误");
        }

        pendingText = "";
        this.draft = null;
        // R40: 每轮 final 是一条独立助手消息——usage 进会话累计，
        // 耗时（本轮流开始→final 到手）挂在消息对象上随会话持久化。
        addUsage(this.sessionUsage, final.usage);
        (final as { durationMs?: number }).durationMs = Date.now() - roundStart;
        if (sessionChanged()) return; // 会话已删除/切换：本轮作废
        this.messages = [...this.messages, final];
        this.persist();
        this.emit();

        const toolCalls = final.content.filter((part): part is ToolCall => part.type === "toolCall");
        if (final.stopReason !== "toolUse" || toolCalls.length === 0) {
          finished = true;
          break;
        }

        for (const call of toolCalls) {
          if (controller.signal.aborted) break;
          // signal 让工具在停止时立刻结算，而不是等 60s/30min 超时。
          let result = await execTool(
            call.name,
            call.arguments ?? {},
            call.id,
            undefined,
            controller.signal,
          );
          // F3: CONFIRM_REQUIRED → 真 UI 弹窗。Kotlin 侧参数闸返回确认请求时，
          // 弹 ConfirmDialog 展示危险摘要；用户允许 → 同参数 + confirm=true 自动重试；
          // 拒绝 → 结果改写为「用户拒绝」，AI 不再自作主张重试。
          if (result.result.startsWith("CONFIRM_REQUIRED:") && !controller.signal.aborted) {
            const summary = result.result.slice("CONFIRM_REQUIRED:".length).trim().slice(0, 300);
            const args = { ...(call.arguments ?? {}) };
            const ok = await appConfirm(`危险操作确认${summary ? "：" + summary : ""}`);
            if (ok) {
              args.confirm = true;
              result = await execTool(call.name, args, call.id, undefined, controller.signal);
            } else {
              result = { ok: false, result: `用户在确认弹窗中拒绝了本次操作。${summary ? `（${summary}）` : ""}` };
            }
          }
          if (sessionChanged()) return; // 会话已删除/切换：本轮作废
          const aborted = controller.signal.aborted;
          this.recordToolResult(call, aborted ? STOPPED_RESULT : result.result, aborted || !result.ok);
          if (aborted) break;
        }
        if (controller.signal.aborted) {
          // 停止时尚未执行的 toolCall 也要落一个中断结果，否则卡片一直转圈。
          for (const call of toolCalls) this.recordToolResult(call, STOPPED_RESULT, true);
          return;
        }

      }

      if (!finished) {
        this.error = `工具调用达到 ${MAX_TOOL_ROUNDS} 轮上限，已停止。如需继续可说「继续」。`;
      }
    } catch (e) {
      if (isAbortError(e) || controller.signal.aborted) {
        this.finishAborted(pendingText);
      } else {
        console.error("agent turn failed", e);
        this.error = friendlyError(e);
      }
    } finally {
      this.running = false;
      this.draft = null;
      this.abort = null;
      setStreamingFlag(false);
      if (!sessionChanged()) this.persist(); // 会话已切换：不把旧轮内容写进新会话
      this.emit();
    }
  }

  /**
   * 中断收尾：保留流式期间已出现的全部内容（文本 + 思考过程 + 已发起的工具
   * 卡片），而不是只存文本——用户点停止不应隐藏已经看到的内容。
   * R53 前 partialThinking/partialTools 直接丢弃，思考块消失。
   */
  private finishAborted(partialText: string): void {
    this.error = null;
    const text = partialText.trim();
    const draft = this.draft;
    const thinking = (draft?.thinking ?? "").trim();
    const tools = draft?.tools ?? [];
    if (!text && !thinking && tools.length === 0) return;
    const content: AssistantMessage["content"] = [];
    if (thinking) content.push({ type: "thinking", thinking } as AssistantMessage["content"][number]);
    if (text) content.push({ type: "text", text });
    const aborted: AssistantMessage = {
      role: "assistant",
      content,
      api: "openai-completions",
      provider: "custom",
      model: this.settings.model,
      usage: ZERO_USAGE,
      stopReason: "aborted",
      errorMessage: "用户已停止生成",
      timestamp: Date.now(),
    };
    this.messages = [...this.messages, aborted];
  }
}
