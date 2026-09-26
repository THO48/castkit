# 贡献者

## 项目作者

- **THO48** —— 发起与维护

## 发送端 v1.0.0 UI 重构

**需求定义、设计决策与验收由项目作者完成**：三个页面的改版范围、Material 3 的取舍、
动态取色与品牌色的冲突处理、分辨率的 Chip 组、移除自定义档位、ViewManager 与 `UiState` 的边界等，
都是逐条拍板后执行的。

**代码实现、构建验证与界面截图由 AI 编码代理产出**：

- **[DeepSeek](https://github.com/deepseek-ai)** —— 模型与推理（`deepseek-flash`）
- **DSH（DeepSeek Harness）** —— 编码代理运行环境

产出内容：发送端从 Miuix 迁移到 Material 3、三个页面与三个 ViewModel 重写、
11 个公共组件、设计系统与 WCAG 实测对比度矩阵、端到端投送测试，
以及在真机/模拟器上定位并修复的两处缺陷（镜像投屏主线程阻塞连接、debug 源集多余的启动图标）。

- 设计系统与线框：[`docs/DESIGN-SYSTEM.md`](docs/DESIGN-SYSTEM.md)
- 界面截图：[`docs/screenshots/`](docs/screenshots)
