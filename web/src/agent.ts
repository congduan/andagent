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
import { execTool, type Settings } from "./bridge";
import { PERMISSION_GUIDE, PERMISSION_GUIDE_EN } from "./permissionGuide";
import { currentLanguage } from "./i18n";
import { isModelConfigured } from "./modelConfig";
import { toModelMessages } from "./modelMessages";
import { SessionStore, type SessionMeta } from "./sessions";
import { EXTRA_TOOL_DEFS } from "./extraTools";
import { extensionTools, skillPrompt, memoryPrompt } from "./bridge";
import apiTools from "../../app/src/main/assets/api_tools.json";
import { addUsage, emptyUsage, sumMessageUsage, type UsageSum } from "./usage";

type PiModule = typeof import("@mariozechner/pi-ai");

const MAX_TOOL_ROUNDS = 200;

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
  { name: "wifi_toggle", description: "经 Shizuku 开关 Wi-Fi。", parameters: { type: "object", properties: { on: { type: "boolean", description: "true 开启" } }, required: ["on"] } },
  { name: "bt_toggle", description: "经 Shizuku 开关蓝牙。", parameters: { type: "object", properties: { on: { type: "boolean", description: "true 开启" } }, required: ["on"] } },
  { name: "wifi_connect_strong", description: "经 Shizuku 连接 Wi-Fi 并验证状态。", parameters: { type: "object", properties: { ssid: { type: "string", description: "网络名" }, password: { type: "string", description: "密码" }, security: { type: "string", description: "open|wpa2|wpa3" } }, required: ["ssid", "security"] } },
  { name: "wifi_saved_list", description: "经 Shizuku 列出已保存 Wi-Fi 网络。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "wifi_forget", description: "经 Shizuku 删除指定 SSID 的已保存网络。", parameters: { type: "object", properties: { ssid: { type: "string", description: "网络名" } }, required: ["ssid"] } },
  { name: "bt_connect_direct", description: "经 Shizuku 尝试直接连接蓝牙设备，若设备命令不支持则降级 GATT。", parameters: { type: "object", properties: { mac: { type: "string", description: "设备 MAC" } }, required: ["mac"] } },
  { name: "termux_setup", description: "安装并校验 Termux 用户空间；失败时检查 Termux App 回退。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "shell_exec", description: "在 Termux 用户空间执行 shell 命令。", parameters: { type: "object", properties: { cmd: { type: "string", description: "命令" }, timeout_s: { type: "integer", description: "超时秒数，默认 60" } }, required: ["cmd"] } },
  { name: "pkg_install", description: "安装 Termux 包；白名单外须 confirm=true。", parameters: { type: "object", properties: { pkg: { type: "string", description: "包名" }, confirm: { type: "boolean", description: "确认白名单外安装" } }, required: ["pkg"] } },
  { name: "widget_generate", description: "根据 spec_description 编写 WidgetSpec v2 JSON。schemaVersion=2，name，background=#RRGGBB，padding=0..32，root 为 COLUMN/ROW，children 节点为 COLUMN/ROW/TEXT/SPACER/PROGRESS；TEXT 可用 binding=DATA + source + field。data.sources 为名称到数据源的对象；类型 STATIC(value 对象)、HTTP(https url)、SYSTEM(system=BATTERY/CLOCK/NETWORK/LOCALE/STORAGE/DEVICE_INFO/BATTERY_DETAIL/STEPS_TODAY)、SCRIPT(script 字符串，定义 refresh(ctx) 返回扁平对象；可调用 andagent.tool('steps_today','{}') 并 JSON.parse)。支持 dependsOn、ttlSeconds、fallback、sample。最多 40 节点、6 层、8 数据源。调用后据此输出完整 spec，再调用 widget_save_and_pin。", parameters: { type: "object", properties: { spec_description: { type: "string", description: "用户希望的小组件内容与样式" } }, required: ["spec_description"] } },
  { name: "shortcut_create", description: "请求在桌面添加一键任务图标；点击后打开 AndAgent 并自动发送预置 prompt。启动器会弹出确认框。", parameters: { type: "object", properties: { name: { type: "string", description: "桌面显示名" }, prompt: { type: "string", description: "点击图标后自动发送的指令" }, emoji: { type: "string", description: "可选：图标上的单个 emoji 字符" } }, required: ["name", "prompt"] } },
  { name: "shortcut_list", description: "列出本应用请求创建的桌面一键任务图标及 ID。", parameters: { type: "object", properties: {} } },
  { name: "shortcut_delete", description: "停用指定桌面快捷方式并移除本地记录；Android 不允许应用直接移除已 pin 图标，用户需长按图标拖到卸载才能彻底移除。", parameters: { type: "object", properties: { id: { type: "string", description: "shortcut_list 返回的 ID" } }, required: ["id"] } },
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
  { name: "web_embed", description: "在对话中展示内嵌浏览器按钮，支持 HTTPS 网站；打开后可用 devtools_eval 在该页面执行 JS 检查（需 DevTools 调试开启）。", parameters: { type: "object", properties: { uri: { type: "string", description: "HTTPS 网站地址" } }, required: ["uri"] } },
  { name: "screen_capture", description: "经 scrcpy-server 取一帧屏幕并返回 JPEG 缩略图。", parameters: { type: "object", properties: {}, required: [] } },
  { name: "screen_record_h264", description: "用 scrcpy H.264 录屏到 Movies，可设置时长、码率和最大边长。", parameters: { type: "object", properties: { duration_s: { type: "integer", description: "秒数，默认 30" }, bit_rate: { type: "integer", description: "码率 bps" }, max_size: { type: "integer", description: "最大边长" } }, required: [] } },
  { name: "input_tap", description: "经 Shizuku 模拟屏幕点击。", parameters: { type: "object", properties: { x: { type: "integer", description: "横坐标" }, y: { type: "integer", description: "纵坐标" } }, required: ["x", "y"] } },
  { name: "input_swipe", description: "经 Shizuku 模拟屏幕滑动。", parameters: { type: "object", properties: { x1: { type: "integer", description: "起点 X" }, y1: { type: "integer", description: "起点 Y" }, x2: { type: "integer", description: "终点 X" }, y2: { type: "integer", description: "终点 Y" }, ms: { type: "integer", description: "时长毫秒" } }, required: ["x1", "y1", "x2", "y2"] } },
  { name: "input_text", description: "经 Shizuku 向当前焦点输入框输入文本。", parameters: { type: "object", properties: { s: { type: "string", description: "输入内容" } }, required: ["s"] } },
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
      "开始录屏（Shizuku 提权，无系统录屏授权弹窗）。duration 秒，默认 30，最大 300；立即返回保存路径，可调用 screen_record_stop 提前停止。需要先激活 Shizuku。",
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
      "写入文本文件。dir 为 downloads（Download/andagent/）、documents（Documents/）或 app_private（应用私有目录）；返回完整路径与字节数。",
    parameters: {
      type: "object",
      properties: {
        dir: { type: "string", description: "downloads | documents | app_private，默认 downloads" },
        name: { type: "string", description: "文件名，例如 notes.txt" },
        content: { type: "string", description: "文件内容（UTF-8）" },
      },
      required: ["name"],
    },
  },
  {
    name: "file_read",
    description:
      "读取文本文件。可用 path（绝对路径或 content://），也可用 dir（downloads|documents|app_private）+ name。上限 100KB，二进制文件返回错误。",
    parameters: {
      type: "object",
      properties: {
        path: { type: "string", description: "文件绝对路径或 content:// URI" },
        dir: { type: "string", description: "与 name 搭配：downloads | documents | app_private" },
        name: { type: "string", description: "文件名（与 dir 搭配）" },
      },
      required: [],
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
  { name: "image_edit", description: "按顺序编辑图片并保存。预览图由工具卡片自动展示，回复只需说明操作与 savedPath（可作为下次 source），不要输出 ```image 围栏或粘贴图片数据。ops_json 例如 [{\"op\":\"crop\",\"aspect\":\"16:9\"},{\"op\":\"brightness\",\"value\":20}]。支持 crop/rotate/flip_h/flip_v/resize/brightness/contrast/saturation/sharpness/blur/grayscale/sepia/invert/border/watermark/compose_over。", parameters: { type: "object", properties: { source: { type: "string" }, ops_json: { type: "string" } }, required: ["source", "ops_json"] } },
  { name: "image_collage", description: "拼图并保存；预览图由工具卡片自动展示，回复只需说明布局与 savedPath。ids_or_uris 是图片源 JSON 数组；free 模式用 slots_json 指定每张图的 x/y/w/h。", parameters: { type: "object", properties: { ids_or_uris: { type: "string" }, layout: { type: "string", enum: ["grid2x2", "hstack", "vstack", "free"] }, output: { type: "string", enum: ["jpeg", "png"] }, slots_json: { type: "string" }, spacing: { type: "integer" }, background: { type: "string" }, corners: { type: "integer" } }, required: ["ids_or_uris", "layout"] } },
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
  "- 实时加速度示例：{\"name\":\"加速度\",\"interval_ms\":200,\"code\":\"const s=JSON.parse(andagent.tool('sensor_read',JSON.stringify({sensor:'accelerometer'})));const pts=andagent.kv.get('live_accel','pts')||[];pts.push(s.values.slice(0,3));const next=pts.slice(-60);andagent.kv.set('live_accel','pts',next);live.result({type:'line',points:next})\"}。根据 sensor_read 实际返回结构提取轴值。",
  "- 要画实时曲线时必须提供 render：从 data.points 读点，使用 ctx.beginPath / moveTo / lineTo / stroke 在 canvas 上绘制；省略 render 时卡片只显示本轮 JSON 文本。",
  "- 单 tick 500ms 硬超时；连续 5 次异常自动停止；用户可在卡片上暂停、停止或重启。",
  "- 静态 canvas 2D 绘图用 ```canvas 围栏，内容可为 JS 或 {\"code\":\"...\",\"animate\":true} JSON；运行在 sandbox iframe，代码接收 ctx,t,w,h，animate:true 可随 requestAnimationFrame 重绘。",
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
  "- Accelerometer example: {\"name\":\"Acceleration\",\"interval_ms\":200,\"code\":\"const s=JSON.parse(andagent.tool('sensor_read',JSON.stringify({sensor:'accelerometer'})));const pts=andagent.kv.get('live_accel','pts')||[];pts.push(s.values.slice(0,3));const next=pts.slice(-60);andagent.kv.set('live_accel','pts',next);live.result({type:'line',points:next})\"}.",
  "- For a live curve include render: read data.points and draw with ctx.beginPath, moveTo, lineTo, and stroke. Without render the card shows JSON text.",
  "- Each tick has a hard 500ms timeout. Five consecutive failures stop the loop. The user can pause, stop, or restart it from the card.",
  "- For static 2D drawing, output a ```canvas fence with JS or JSON {\"code\":\"...\",\"animate\":true}. It runs in a sandbox iframe with ctx,t,w,h; animate:true redraws with requestAnimationFrame.",
].join("\n");

const NETWORK_GUIDE = [
  "网络访问：http_request 是通用 HTTP 客户端，支持 GET/POST/PUT/DELETE/PATCH/HEAD，可用 headers_json 传 Authorization 等请求头、body 传请求体；它允许 http:// 与 https://，局域网设备（Home Assistant 的 http://192.168.x.x:8123/api/、ESPHome、路由器 API）优先用它，网页正文仍用 web_read（仅 HTTPS）。",
  "调用局域网 API 前先用 lan_scan 找设备：mode=ping 默认扫当前 Wi-Fi 的 /24，返回存活 IP 与反查主机名；已知端口（如 8123、80、1883）用 mode=port。仅扫描自己所在的网络，不要扫描他人网段；不要用 http_request 对同一地址高频轮询。",
  "http_request 返回 JSON：status/ok/url/headers/body/truncated/elapsedMs。长正文用 max_chars 控制；非 2xx 时先看 status 与 body 前段再决定是否重试，不要盲目重复同一请求。",
].join("\n");

const NETWORK_GUIDE_EN = [
  "Network access: http_request is a generic HTTP client supporting GET/POST/PUT/DELETE/PATCH/HEAD, with headers_json for Authorization-style headers and body for payloads. It allows both http:// and https://, so use it first for LAN devices (Home Assistant at http://192.168.x.x:8123/api/, ESPHome, router APIs); keep web_read (HTTPS only) for web page text.",
  "Find devices with lan_scan before calling a LAN API: mode=ping scans the current Wi-Fi /24 by default and returns live IPs with reverse-DNS hostnames; use mode=port for a known port (8123, 80, 1883). Scan only the network you are on, never someone else's range, and do not hammer one address through http_request.",
  "http_request returns JSON with status/ok/url/headers/body/truncated/elapsedMs. Bound long bodies with max_chars; on non-2xx inspect status and the body prefix before deciding whether to retry, and do not blindly repeat the same request.",
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
  "红外遥控：先用 ir_available 检测发射器；从网上或遥控器数据库查到目标品牌/型号的 Pronto Hex 0000 码后，用 ir_send_pronto 发送；自定义协议用 ir_send_raw 发送交替 on/off 微秒数组。空调等多状态设备应使用包含温度、模式、风速等完整状态的品牌码。手机无红外硬件时如实告知，不能声称已发送。电视、机顶盒、音响、红外灯具也可按匹配码遥控。",
  "AirPods：airpods_scan 扫描附近广播；airpods_status / airpods_battery 查询近期状态和电量。",
  "airpods_monitor_start 开启可从通知停止的后台监听；airpods_monitor_stop 停止；airpods_history 取电量与佩戴历史，可用 ```chart 画续航趋势。",
  "监听值写入 KV 的 airpods/latest 和 airpods/history。```live 循环可用 andagent.kv.get('airpods','latest') 读取；检测佩戴状态 true→false 时用 media_send_key(keycode=pause) 暂停。低电量可调用 notify_send；跨 tick 保存上次状态避免重复触发。",
  "系统事件：媒体会话与通知需先用 notif_permission 在系统设置授权，再开启 media_events_on / notif_events_on；音频设备用 audio_events_on。各通道的 KV 命名空间为 media_events、notif_events、audio_events，latest 与 history 键可供 live 轮询；使用 timestamp 去重。用对应 off 工具或前台通知停止监听。音频设备回调报告添加/移除，不保证获知实际音频路由切换。",
].join("\n");

const HARDWARE_GUIDE_EN = [
  "Infrared remote: check ir_available first. Find a matching brand/model Pronto Hex 0000 code via web search or a remote-code database, then send it with ir_send_pronto. Use ir_send_raw for custom on/off microsecond patterns. For stateful air conditioners, use a complete brand code encoding temperature, mode and fan speed. If the phone lacks an IR emitter, report that accurately. TVs, set-top boxes, audio systems and IR lights also work with matching codes.",
  "AirPods: use airpods_scan to discover nearby broadcasts; airpods_status / airpods_battery for recent status and charge.",
  "airpods_monitor_start enables background monitoring with a notification stop action; airpods_monitor_stop stops it. airpods_history supplies battery and in-ear history for a ```chart trend.",
  "Monitor data is available in KV at airpods/latest and airpods/history. A ```live loop can read andagent.kv.get('airpods','latest'). On an in-ear true-to-false transition, call media_send_key(keycode=pause); use notify_send for low charge. Save prior state across ticks to avoid repeats.",
  "System events: media sessions and notifications require notification access via notif_permission before media_events_on / notif_events_on. Audio devices use audio_events_on. Poll latest or history in media_events, notif_events and audio_events KV namespaces from live, deduplicating by timestamp. Use the matching off tool or foreground notification to stop. Audio callbacks report device additions/removals, not guaranteed route changes.",
].join("\n");

const SHORTCUT_GUIDE = [
  "桌面一键任务：用户说‘帮我做个一键打卡的桌面图标’等需求时，调用 shortcut_create，提供显示名和点击后自动发送的完整 prompt，可选单个 emoji 图标。启动器会要求用户确认 pin。",
  "小组件 widget_generate 是桌面常驻信息卡；快捷方式 shortcut_create 是点击后执行预置任务。列出已有图标用 shortcut_list；shortcut_delete 只能停用，彻底移除需用户长按桌面图标拖到卸载。",
].join("\n");

const SHORTCUT_GUIDE_EN = [
  "Desktop one-tap tasks: when the user asks for a desktop icon that runs a task, call shortcut_create with a label and the full prompt to send automatically on tap; an emoji icon is optional. The launcher asks the user to confirm pinning.",
  "widget_generate creates a persistent desktop information card; shortcut_create creates a one-tap task. Use shortcut_list to find IDs. shortcut_delete only disables a pinned icon; the user must long-press and drag it to Remove to remove it completely.",
].join("\n");

const STORAGE_MEDIA_GUIDE = [
  "文本转音频文件用 tts_to_file；返回 path 后，用 ```audio 围栏展示，内容为 JSON：{\"uri\":\"返回的 path\",\"title\":\"音频\"}。用户可点播放器播放；文件保存在应用 exports 目录。",
  "语音输入用 speech_recognize：用户说话（默认 zh-CN，可用 language 指定）→ 返回 text/confidence → 再按需求处理；它与 tts_to_file 构成「听/说」闭环。设备无系统识别服务时如实报错，不要用 mic_record 假装转写。",
  "结构化存储：用户要记账、清单或笔记库时，先用 db_create 建库，再用 db_exec 建表，后续用参数化 INSERT 和 db_query SELECT。",
  "轻量键值：用户要记住偏好、计数器或小型状态时，用 kv_set 写入，kv_get/kv_keys 读取；临时数据加 ttl_ms；清空命名空间用 kv_clear 且 confirm=true；备份/恢复用 kv_export/kv_import（replace 需 confirm=true）（最多 64 个命名空间、每命名空间 512 键、单值 64KB）。",
  "记账表例：CREATE TABLE records(id INTEGER PRIMARY KEY, date TEXT, item TEXT, amount REAL)。",
  "输出呈现：给用户音频或视频文件时，用 ```audio 或 ```video 围栏，内容为 JSON，例如 {\"uri\":\"/本地路径/file.mp4\",\"title\":\"文件名\"}；uri 可为本地文件路径或 https。",
].join("\n");

const STORAGE_MEDIA_GUIDE_EN = [
  "Use tts_to_file to create an audio file from text. After it returns path, show a ```audio fence containing JSON {\"uri\":\"returned path\",\"title\":\"Audio\"}. The user can play it in the card; the file is saved in the app exports directory.",
  "Use speech_recognize for voice input: the user speaks (default zh-CN; set language for other locales) and you get text/confidence to act on. Together with tts_to_file it closes the listen/speak loop. If the device has no recognition service, report the error instead of pretending mic_record transcribed audio.",
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
  "3. 只能使用以下 catalog 组件：TEXT_INPUT（key,label,placeholder,required,max_length,validate 取 email|phone|regex,pattern）、NUMBER_SLIDER（min,max,step,default）、CHOICE（options 为字符串数组或 {value,label} 数组，multiple 可选）、DATE_PICK、SWITCH（default）、BUTTON_GROUP（buttons）、CARD（title,text）、ROW/COLUMN（children 递归嵌套）、LABEL（text）。",
  "4. 每个字段 key 必须唯一；总节点数不超过 60；只适合 2-8 个字段的轻量表单，不要生成超大表单；ROW/COLUMN 最多嵌套 8 层。",
  '5. 用户提交后，卡片会把表单值以 {"a2ui_submit":{...},"a2ui_title":"标题"} 回填到输入框；用户确认发送后你才会收到。收到后直接按其中的字段继续任务，不要重复追问。',
  "6. 示例：",
  "```a2ui",
  '{"title":"导出设置","submit_label":"提交","fields":[{"type":"TEXT_INPUT","key":"email","label":"接收邮箱","placeholder":"you@example.com","required":true,"validate":"email"},{"type":"CHOICE","key":"format","label":"导出格式","options":["Markdown","PDF"]},{"type":"SWITCH","key":"include_images","label":"包含图片","default":true}]}',
  "```",
].join("\n");

const A2UI_GUIDE_EN = [
  "Structured form cards (```a2ui fence):",
  "1. When the user must pick options, fill in parameters, or confirm choices, output an ```a2ui fence with valid JSON instead of asking in plain text.",
  '2. Top-level shape: {"title":"Title","submit_label":"Submit","fields":[...]}; fields must be a non-empty array.',
  "3. Use only these catalog components: TEXT_INPUT (key,label,placeholder,required,max_length,validate as email|phone|regex,pattern), NUMBER_SLIDER (min,max,step,default), CHOICE (options as a string array or {value,label} array, optional multiple), DATE_PICK, SWITCH (default), BUTTON_GROUP (buttons), CARD (title,text), ROW/COLUMN (nested children), LABEL (text).",
  "4. Every field key must be unique; keep the total node count at or below 60; use this only for lightweight forms of 2-8 fields, never huge forms; ROW/COLUMN nesting is limited to 8 levels.",
  '5. After the user submits, the card fills {"a2ui_submit":{...},"a2ui_title":"Title"} into the composer; you receive it only after the user presses send. Then continue the task using those fields and do not ask again.',
  "6. Example:",
  "```a2ui",
  '{"title":"Export settings","submit_label":"Submit","fields":[{"type":"TEXT_INPUT","key":"email","label":"Email","placeholder":"you@example.com","required":true,"validate":"email"},{"type":"CHOICE","key":"format","label":"Format","options":["Markdown","PDF"]},{"type":"SWITCH","key":"include_images","label":"Include images","default":true}]}',
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
    input: ["text"],
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
      const text =
        typeof message.content === "string"
          ? message.content
          : message.content.map((p) => (p.type === "text" ? p.text : "")).join("");
      ui.push({
        id: `u-${index}-${message.timestamp}`,
        role: "user",
        content: [{ type: "text", text }],
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

  stop(): void {
    this.abort?.abort();
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

  async send(text: string): Promise<void> {
    const content = text.trim();
    if (!content || this.running) return;
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
    const userMessage: UserMessage = { role: "user", content, timestamp: Date.now() };
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
      let rounds = 0;
      let finished = false;
      while (rounds < MAX_TOOL_ROUNDS) {
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
              currentLanguage() === "en" ? HARDWARE_GUIDE_EN : HARDWARE_GUIDE,
              currentLanguage() === "en" ? NETWORK_GUIDE_EN : NETWORK_GUIDE,
              currentLanguage() === "en" ? SSH_TRANSFER_GUIDE_EN : SSH_TRANSFER_GUIDE,
              currentLanguage() === "en" ? SHORTCUT_GUIDE_EN : SHORTCUT_GUIDE,
              currentLanguage() === "en" ? STORAGE_MEDIA_GUIDE_EN : STORAGE_MEDIA_GUIDE,
              currentLanguage() === "en" ? MEDIA_CONTEXT_GUIDE_EN : MEDIA_CONTEXT_GUIDE,
              currentLanguage() === "en" ? A2UI_GUIDE_EN : A2UI_GUIDE,
              currentLanguage() === "en" ? PERMISSION_GUIDE_EN : PERMISSION_GUIDE,
            ]
              .filter(Boolean)
              .join("\n\n"),
            // Compact projection: base64 tool payloads never reach the prompt.
            messages: toModelMessages(this.messages),
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
        this.messages = [...this.messages, final];
        this.persist();
        this.emit();

        const toolCalls = final.content.filter((part): part is ToolCall => part.type === "toolCall");
        if (final.stopReason !== "toolUse" || toolCalls.length === 0) {
          finished = true;
          break;
        }

        for (const call of toolCalls) {
          const result = await execTool(call.name, call.arguments ?? {}, call.id);
          if (controller.signal.aborted) return;
          const toolResult: ToolResultMessage = {
            role: "toolResult",
            toolCallId: call.id,
            toolName: call.name,
            content: [{ type: "text", text: result.result }],
            isError: !result.ok,
            timestamp: Date.now(),
          };
          this.messages = [...this.messages, toolResult];
          this.persist();
          this.emit();
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
      this.persist();
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
