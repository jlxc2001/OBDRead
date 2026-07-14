# OBD Speed Demo v0.2

一个用于验证廉价 ELM327 / OBDII 蓝牙模块的 Android Demo。

## v0.2 连接修复

本版修复部分廉价 OBDII / ELM327 蓝牙模块无法连接的问题：

- 优先尝试 `createInsecureRfcommSocketToServiceRecord`。
- 再尝试标准 Secure SPP。
- 再尝试隐藏 API `createInsecureRfcommSocket(channel)` / `createRfcommSocket(channel)`。
- 兜底扫描 RFCOMM channel 1-30。
- 日志会显示每一种连接方式是否成功，便于继续排查。

如果 Torque / ELM327 Identifier 能连，但本 Demo 不能连，请先完全退出其它 OBD 软件，因为同一时间通常只能有一个 APP 占用 OBD 蓝牙串口。

## 当前功能

- 读取系统已经配对的经典蓝牙设备
- 使用经典蓝牙 SPP UUID 连接 ELM327
- 初始化通用 ELM327：`ATZ / ATE0 / ATL0 / ATS0 / ATH0 / ATAT1 / ATSP0`
- 标准 OBD2 PID 读取：
  - 车速：`010D`
  - 发动机转速：`010C`
  - 冷却液温度：`0105`
  - 电压：`ATRV`
- 原始返回日志显示，方便排查 `NO DATA`、`SEARCHING...`、`UNABLE TO CONNECT` 等问题
- HTTP 接口，默认端口 `47240`
  - `/data`
  - `/speed`
  - `/status`
  - `/raw`

## 使用方法

1. 把 OBD 蓝牙模块插到车辆 OBD 口。
2. 车辆上电，最好启动发动机。只通电时有些车的 ECU 不一定完整响应。
3. 到 Android 系统蓝牙设置里手动配对设备。
   - 常见名称：`OBDII`、`OBD2`、`ELM327`
   - 常见配对码：`1234`、`0000`
4. 打开本 Demo。
5. 授权蓝牙权限。
6. 点“刷新设备”，选择 `OBDII`。
7. 点“连接初始化”。
8. 点“开始读取”。

## HTTP 示例

访问：

```text
http://手机IP:47240/data
```

返回示例：

```json
{
  "speedKmh": 60,
  "rpm": 1800,
  "coolantC": 86,
  "voltage": "13.9 V",
  "elmConnected": true,
  "ecuConnected": true,
  "reading": true,
  "protocol": "AUTO, ISO 15765-4 (CAN 11/500)",
  "pidSupport": {
    "speed010D": true,
    "rpm010C": true,
    "coolant0105": true
  }
}
```

## 兼容说明

这个 Demo 只做标准 OBD2 通用数据验证。车速、转速、水温一般比较通用；车门、安全带、转向灯、远近光通常属于车身 CAN / BCM 私有信号，不能指望普通 ELM327 用标准 PID 直接通用读取。

## 构建

### Android Studio

直接用 Android Studio 打开本目录，然后 Build APK。

### GitHub Actions

仓库里已经带 `.github/workflows/android-debug.yml`。推到 GitHub 后，手动运行 `Android Debug Build`，会生成 debug APK artifact。

### 命令行

本项目没有附带 Gradle Wrapper。电脑上有 Android SDK 和 Gradle 时可以运行：

```bash
gradle assembleDebug --no-daemon --stacktrace
```

输出路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

