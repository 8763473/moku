# 墨库 MoKu

一个面向长篇写作的原生 Android AI 工作台。界面借鉴 LM Studio 的聊天与上下文反馈方式，同时把“以前说过的要求”做成可编辑、可检索、可固定调用的本地知识库。

## 已实现

- OpenAI 兼容的自定义 API：Base URL、API Key、模型 ID、Temperature、流式开关。
- 兼容 LM Studio：支持本地服务和局域网地址，API Key 可留空。
- 思考过程：优先读取 `reasoning_content` / `reasoning`，也能拆分 `<think>...</think>`。
- 上下文容量：显示估算 token 占用，并在请求前按配置容量裁剪较早历史，预留生成空间。
- 本地知识库：要求、设定、人物、资料四类；支持标签、优先级、启停和固定调用。
- 自动回忆：发送前在本地检索相关条目，固定条目始终注入，命中内容会显示在聊天页。
- 本地持久化：Room 保存会话、消息和知识条目；自定义设置保存在应用私有目录。

## 连接 LM Studio

1. 在电脑的 LM Studio 中加载模型并启动 Local Server。
2. 允许局域网访问；确认手机和电脑连接同一网络。
3. 在“设置”中填写 `http://电脑局域网IP:1234/v1`，例如 `http://192.168.1.10:1234/v1`。
4. 填写 LM Studio 中显示的模型 ID，API Key 留空，然后点“测试 API 连接”。

Android 模拟器访问本机可使用默认地址 `http://10.0.2.2:1234/v1`。真机不能用 `127.0.0.1` 访问电脑。

## 构建

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 知识库调用规则

- “固定调用”适合全局文风、禁忌和绝不能违背的设定，会占用每次请求的上下文。
- 普通条目通过标题、内容和标签做本地中文关键词匹配，不会把知识发送给额外的云端检索服务。
- 命中条目会放入 system 消息中的独立知识块；聊天页会显示本次实际调用的条目。
- 思考过程仅用于当前界面展示，不会作为下一轮对话历史重新发送。

## 项目结构

- `data/`：Room 数据库、实体、DAO 与 API 设置。
- `domain/ChatTools.kt`：token 估算、思考拆分、知识检索和上下文裁剪。
- `network/ChatApiClient.kt`：OpenAI 兼容流式/非流式请求。
- `ui/`：聊天、知识库、设置和主题界面。
