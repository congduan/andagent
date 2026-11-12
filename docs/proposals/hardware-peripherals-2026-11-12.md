# AndAgent 硬件外设能力扩展建议（低权限优先）

## 已有能力基座（盘点结论）

USB 串口 + ESP32 烧录（stub loader + read_flash）/ 蓝牙 BLE 扫描 + AirPods 监控 + HID device 角色 + HCE 卡模拟 / NFC 读写 + 碰一碰自动分析 / 传感器（sensor_read）/ 相机 RAW / 麦克风 / 无障碍 / 悬浮窗 / 本地 VPN 抓包 / 全屏 pty 终端 / cron。定位权限、电池白名单已有引导。

## 一、USB OTG 外设系（零权限！只要 USB 权限对话框一次）

### A. USB HID 设备控制（高价值）
- **USB 键盘/小键盘/宏键盘**：usb-serial 层之外新增 USB HID host（raw descriptor 解析）
- 玩法：AI 读取键盘输入事件、宏编程（把 K2 宏键盘配置成 AndAgent 快捷指令触发器——按一个键让 AI 干活）
- 权限：USB device 权限对话框一次，无敏感权限

### B. USB 摄像头（UVC）
- 外接 USB 摄像头 → UVC 协议（部分国产镜头免驱）→ AI 视觉分析
- 玩法：接工业微距镜头做「AI 质检」「电路板/元件识别」；接Endoscope 内窥镜做管道检测
- 已有相机管线可复用（Surface + ImageAnalysis）

### C. USB 网络共享（RNDIS）→ 有线网
- USB 转 100M/千兆网卡（RNDIS/AX88179）——手机插网线！与现有 VPN 抓包叠加做「有线流量镜像分析」

### D. USB 游戏/测试仪表
- USB 功率计（UM34C/UM25C 等充电头测试仪，协议开源）→ AI 读电压电流曲线，测充电功率
- USB 温湿度计/CO2 传感器（HID 或 CDC 类）
- 玩法：「帮我测一下这个充电宝实际输出多少瓦」

## 二、BLE 外设系（只需 BLUETOOTH_SCAN，已有）

### E. BLE 传感器广播读取（高价值低成本）
- 温湿度计广播解码：小米/青萍 LYWSD03MMC（广播明文温湿度，无需连接）
- 玩法：「客厅温度多少」→ AI 直接读广播包回答，秒回零延迟
- 现有 PodsMonitor 的 BLE scan 架构直接复用（换 manufacturer data 解析）

### F. BLE 体脂秤/血压计/血糖仪（标准 GATT）
- 开放协议（Weight Scale Profile / Blood Pressure Profile）→ AI 记录健康数据到本地

### G. 追踪器反查（Tile/AirTag/小米追踪器）
- 扫描周边 BLE 追踪器广播 → 「我周围有没有跟踪我的 AirTag？」——反追踪隐私工具

### H. BLE 遥控器（HID over GATT）
- BLE 遥控器/自拍杆/游戏手柄 → AI 读取按键事件绑定为 AndAgent 快捷触发

## 三、NFC 延伸系（NFC 权限已有）

### I. NFC 门禁卡模拟扩展
- HCE 已有 → 对接 Mifare Classic 读卡（已有）→ 「读小区门禁卡 → 分析协议 → HCE 模拟」（仅限可读卡型，合规边界战报注明）
### J. NDEF 智能贴纸编程
- 写 NTF 贴纸 = AI 快捷指令触发器：贴纸贴桌面，碰一下手机 → 自动执行预设 AI 任务（和 R145 碰一碰天然衔接）

## 四、内置传感器系（零权限）

### K. 传感器组合玩法
- sensor_read 已有 → 组合自动化：磁力计+加速度计 = 简易金属探测/水平仪；步态检测；「手机放冰箱里测降温曲线」（温度+时间序列）
- 音频频谱：麦克风 → FFT → AI 听声辨设备（电机异响/蜂鸣器频率）

### L. 光线/接近传感器自动化
- 光敏触发：cron + sensor_read = 「每天早上拉窗帘光线足够时自动播报天气」

## 优先级建议（可玩性 × 成本）

1. **E BLE 温湿度广播**——成本最低（复用 PodsMonitor 架构），家里有小米温湿度计立刻可用
2. **D USB 功率计**——极客感最强，UM34C 一百多块硬件，协议开源
3. **B UVC 外接摄像头**——打开「AI 视觉质检/内窥镜」场景
4. **J NDEF 贴纸快捷指令**——与 R145 天然衔接，纯软件成本
5. **G 反追踪扫描**——隐私工具属性，社媒传播性好

## 不建议（权限/合规风险高）

- 基站/Cell 扫描（要 READ_PHONE_STATE 敏感权限）
- Wi-Fi P2P/Wi-Fi Direct 大规模扫描（定位权限+滥用风险）
- SIM/电话能力（运营商标识等敏感面）
- 红外遥控（Blaster 需要特定硬件，且 IR 发射对家电合规风险）——其实 IR 是低风险可玩（IR send 需 consumer IR API + 硬件支持），可作备选
