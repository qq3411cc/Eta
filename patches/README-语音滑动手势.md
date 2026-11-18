# Time-IU/Eta 语音上滑发送、下滑取消

本分支只存放一个 patch：`patches/TimeIU-语音滑动手势.patch`。

适用对象：`Time-IU/Eta` main（3.4.4，AGP 9.5.1 + COUI alpha11）。

改动：
- `EtaAssistantComposer.kt`：`AssistantVoiceEntry` 外层 Row 加 `detectVerticalDragGestures`（56 dp 阈值），
  上滑松手 = 结束并发送，下滑松手 = 取消；语音条文字实时提示
- `strings.xml`：新增 `voice_swipe_up_send`、`voice_swipe_down_cancel`
- `proguard-rules.pro`：加 `-dontoptimize`，避免 R8 优化后加载本地模型在 ART `HeapTaskDaemon` 上 SIGABRT

应用：

```bash
cd <Time-IU/Eta 源码根>
patch -p1 < patches/TimeIU-语音滑动手势.patch
```
