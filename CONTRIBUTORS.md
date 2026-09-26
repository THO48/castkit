# 贡献者

<p>
<a href="https://github.com/THO48"><img src="https://avatars.githubusercontent.com/u/41006094?v=4" width="88" alt="THO48"></a>
&nbsp;&nbsp;
<a href="https://github.com/deepseek-ai"><img src="https://avatars.githubusercontent.com/u/148330874?v=4" width="88" alt="DeepSeek"></a>
</p>

| | 角色 |
|---|---|
| **[THO48](https://github.com/THO48)** | 项目发起与维护 |
| **[DeepSeek](https://github.com/deepseek-ai)** | 发送端 v1.0.0 UI 重构的模型与推理（`deepseek-flash`） |
| **DSH（DeepSeek Harness）** | 上述工作的编码代理运行环境 |

> DSH 目前没有可用的 GitHub 账号，所以头像行里没有它。想让它也出现在头像行，
> 需要一个 GitHub 账号（用户或 bot 都行），把账号名给我即可补上。

## 发送端 v1.0.0 UI 重构的分工

**需求定义、设计决策与验收由项目作者完成**：三个页面的改版范围、Material 3 的取舍、
动态取色与品牌色的冲突处理、分辨率的 Chip 组方案、移除自定义分辨率档位、
ViewModel 与 `UiState` 的边界等，都是逐条拍板后执行的。

**代码实现、构建验证与界面截图由 AI 编码代理产出**：发送端从 Miuix 迁移到 Material 3、
三个页面与三个 ViewModel 重写、11 个公共组件、设计系统与 WCAG 实测对比度矩阵、
端到端投送测试，以及在真机/模拟器上定位并修复的两处缺陷
（镜像投屏主线程阻塞连接、debug 源集多余的启动图标）。

- 设计系统与线框：[`docs/DESIGN-SYSTEM.md`](docs/DESIGN-SYSTEM.md)
- 界面截图：[`docs/screenshots/`](docs/screenshots)
