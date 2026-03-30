[English](README.md) | [中文](README_ZH.md) | [한국어](README_KO.md)

# AION2 DPS Meter
> https://a2tools.app

一款适用于 **AION 2**（永恒之塔2）台服或韩服的战斗分析（DPS 统计）工具。

我们的目标是打造一款社区工具，不依赖任何可能以违反服务条款的方式干扰游戏的方法。本工具**免费提供**，[可直接安装。](#安装方法)

如果您想参与贡献，可以通过下方链接加入我们的 Discord！

- 🔗 **GitHub 仓库：** https://github.com/taengu/Aion2-Dps-Meter
- 🔗 **Gitee 仓库：** https://gitee.com/taengu/AION2-DPS-Meter
- 💬 **Discord（技术支持与社区）：https://discord.gg/Aion2Global**

[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![GitHub Issues](https://img.shields.io/github/issues/taengu/Aion2-Dps-Meter)](https://github.com/taengu/Aion2-Dps-Meter/issues)
[![GitHub Pull Requests](https://img.shields.io/github/issues-pr/taengu/Aion2-Dps-Meter)](https://github.com/taengu/Aion2-Dps-Meter/pulls)

基于 [Aion2-Dps-Meter](https://github.com/TK-open-public/Aion2-Dps-Meter) 分支开发

> **重要声明**
> 如果游戏运营商提出要求、引入数据包加密或其他反制措施，或官方发表禁止使用的声明，本项目将**暂停或转为私有**。

> **警惕仿冒和盗版！**
> 市面上有其他 DPS 统计器在重新打包本项目的代码 — 有些收费出售，有些甚至不公开源代码。本工具现在和将来都是**免费且开源的**。唯一的官方来源是本 GitHub 仓库。如果有人向你出售 AION 2 DPS 统计器，你可能正在被骗。

---

## 安装方法

### 第一步 — 安装 Npcap（必需）

从 https://npcap.com/#download 下载并安装 **Npcap**。

> ⚠️ 安装过程中，**必须**勾选 **"Install Npcap in WinPcap API-compatible Mode"**。
> 不启用此选项，统计器将无法工作。

### 第二步 — 下载并安装统计器

👉 从 [Releases 页面](https://github.com/taengu/Aion2-Dps-Meter/releases) 获取最新安装包。

运行安装程序并按提示操作。

### 第三步 — 启动

通过桌面快捷方式或开始菜单打开 **AION2 DPS Meter**。

### 第四步 — 允许 Windows 防火墙

首次启动时，Windows 会请求网络访问权限。
点击**允许** — 展开提示并同时勾选**专用**和**公用**网络，以确保不会遗漏数据。

### 故障排除

如果统计器停止显示数据：
1. 点击统计器窗口中的**刷新**图标。
2. 如果没有效果，请关闭统计器并重新打开。
3. 仍然无法使用？退出游戏到角色选择界面，重新启动统计器，然后重新进入游戏。

---

## 界面说明

<img width="439" height="288" alt="image" src="https://github.com/user-attachments/assets/eae5dfd9-25c1-4e38-821f-6af0012acc93" />


- **蓝色框** – 怪物名称显示（计划中）
- **棕色框** – 重置当前战斗数据
- **黄色框** - 切换显示 DPS 或总伤害
- **粉色框** – 展开/折叠 DPS 统计器
- **红色框** – 职业图标（检测到时显示）
- **橙色框** – 玩家昵称（点击查看详情）
- **浅蓝色框** – 当前目标的 DPS
- **紫色框** – 贡献百分比
- **绿色框** – 战斗计时器
  - 绿色：战斗中
  - 黄色：未检测到伤害（暂停）
  - 灰色：战斗结束
- **黑色框** - ID 占位符，玩家名称仍在搜索中时显示

点击玩家行可查看详细统计信息。

> **命中次数**指的是**成功命中次数**，而非技能释放次数。


## 构建说明
> ⚠️ **普通用户无需自行构建项目。**
> 本节仅供开发者参考。

```bash
# 克隆仓库
git clone https://github.com/taengu/Aion2-Dps-Meter.git

# 进入目录
cd Aion2-Dps-Meter

# 在 IntelliJ 终端中运行（输出保持在同一窗口）
# 如果 IntelliJ 打开了单独的 cmd 窗口，请在
# Settings > Build, Execution, Deployment > Gradle 中启用 "Run in terminal"。
./gradlew run

# 构建发行版（Windows）
./gradlew packageDistributionForCurrentOS
```



---

## 常见问题

**问：与原版统计器有什么区别？**
- 原版针对韩服编写，使用硬编码方式查找游戏数据包。
- 本版本增加了自动检测功能，支持 VPN/加速器。同时已将技能/法术名称和界面翻译为英文。

**问：所有名称/我的名称显示为数字？**
- 由于游戏不会频繁发送名称信息，名称检测可能需要一些时间
- 您可以使用传送卷轴或传送到军团来加快名称检测
- 为节省传送次数，如果您使用 Exitlag，可以启用"快捷重启所有连接"选项，用它重新加载游戏以更快填充名称。

**问：界面显示正常，但没有伤害数据。**
- 检查 Npcap 是否正确安装
- 退出应用，回到角色选择界面，然后重新启动

**问：能看到其他人的 DPS 但看不到自己的。**
- DPS 基于总伤害最高的怪物进行计算
- 请使用与统计器上已显示的玩家相同的训练木桩。

**问：独自一人时贡献率不是100%。**
- 可能是名称捕获失败

**问：某些技能显示为数字。**
- 这些通常是神石（Theostones）
- 其他情况请通过 GitHub Issues 反馈

---

## 下载

👉 https://github.com/taengu/Aion2-Dps-Meter/releases

请勿根据 DPS 结果骚扰其他玩家。
使用风险自负。

---

## 社区与支持

- 💬 **加入我们的 Discord：** https://discord.gg/Aion2Global
- **感谢支持，资助新的酷炫项目和功能！**
  - ☕ [请我喝杯咖啡](https://ko-fi.com/hiddencube)
  - ☕ [在爱发电支持我](https://afdian.com/a/hiddencube)
  - 🅿️ [通过 PayPal 打赏](https://www.paypal.me/taengoo)
  - 🎁 [使用加密货币捐赠](https://nowpayments.io/donation/thehiddencube)
  - **BTC**: `1GexKhgVZPYRqpfCKydXLoNUXRRRUoAUwT`
  - **ETH**: `0x38F0bc371A563A24eCa6034cFf77eB6173c7e3e7`
  - **USDC**: `0xA9571Fc95666350f6DFFB8Fb80ee27eE7db46b56`
