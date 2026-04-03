[English](README.md) | [中文](README_ZH.md) | [한국어](README_KO.md)

# AION2 DPS Meter by [A2tools.app](https://a2tools.app)
> We're migrating to a new rewrite - see https://github.com/taengu/A2Tools-DPS-Meter

A combat analysis (DPS meter) tool for **AION 2**, Taiwan or Korea servers.

Our goal is to make a community tool that doesn't rely on methods that might intefere with the game in ways that break the terms of service. This tool is offered **for free**, [ready to install.](#how-to-install)

If you'd like to get involved, you can reach us on Discord from the link below!

- 🔗 **GitHub Repository:** https://github.com/taengu/Aion2-Dps-Meter
- 🔗 **Gitee Repository:** https://gitee.com/taengu/AION2-DPS-Meter
- 💬 **Discord (Support & Community): https://discord.gg/Aion2Global**

[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![GitHub Issues](https://img.shields.io/github/issues/taengu/Aion2-Dps-Meter)](https://github.com/taengu/Aion2-Dps-Meter/issues)
[![GitHub Pull Requests](https://img.shields.io/github/issues-pr/taengu/Aion2-Dps-Meter)](https://github.com/taengu/Aion2-Dps-Meter/pulls)

Lovingly forked from [Aion2-Dps-Meter](https://github.com/TK-open-public/Aion2-Dps-Meter)
 
> **Important Notice**
> This project will be **paused or made private** if requested by the game operator, if packet encryption or other countermeasures are introduced, or if there is an official statement prohibiting its use.

> **Beware of clones and fakes!**
> There are other DPS meters circulating that repackage this project's code — some charge money for it, and others don't even release their source code. This tool is and always will be **free and open-source**. The only official source is this GitHub repository. If someone is selling you an AION 2 DPS meter, you might be being scammed.

---

## How to Install

### Step 1 — Install Npcap (required)

Download and install **Npcap** from https://npcap.com/#download

> ⚠️ During installation, you **must** check **”Install Npcap in WinPcap API-compatible Mode”**.
> The meter will not work without this option enabled.

### Step 2 — Download & install the meter

👉 Grab the latest installer from the [Releases page](https://github.com/taengu/Aion2-Dps-Meter/releases).

Run the installer and follow the prompts.

### Step 3 — Launch

Open **AION2 DPS Meter** from the Desktop shortcut or Start Menu.

### Step 4 — Allow through Windows Firewall

On first launch, Windows will ask to allow network access.
Click **Allow** — expand the prompt and tick both **Private** and **Public** networks to ensure no data is missed.

### Troubleshooting

If the meter stops showing data:
1. Click the **reload** icon in the meter window.
2. If that doesn't help, close the meter and reopen it.
3. Still not working? Exit the game to character select, relaunch the meter, then re-enter the game.

---

## UI Explanation

<img width="439" height="288" alt="image" src="https://github.com/user-attachments/assets/eae5dfd9-25c1-4e38-821f-6af0012acc93" />


- **Blue box** – Monster name display (click to open History)
- **Brown box** – Reset current combat data
- **Yellow box** - Toggle between showing DPS or total DMG
- **Pink box** – Expand / collapse DPS meter
- **Red box** – Class icon (shown when detected)
- **Orange box** – Player nickname (click for details)
- **Light blue box** – DPS for current target
- **Purple box** – Contribution percentage
- **Green box** – Combat timer  
  - Green: in combat  
  - Yellow: no damage detected (paused)  
  - Grey: combat ended
- **Black box** - ID placeholder, when player name is still being searched for

Clicking a player row opens detailed statistics.


## Build Instructions
> ⚠️ **Regular users do NOT need to build the project.**  
> This section is for developers only.

```bash
# Clone the repository
git clone https://github.com/taengu/Aion2-Dps-Meter.git

# Enter the directory
cd Aion2-Dps-Meter

# Run in IntelliJ Terminal (keeps output in the same window)
# If IntelliJ starts a separate cmd window, enable "Run in terminal" under
# Settings > Build, Execution, Deployment > Gradle.
./gradlew run

# Build the distribution (Windows)
./gradlew packageDistributionForCurrentOS
```

---

## FAQ

**Q: What's different from the original meter?**
- The original was written for KR servers and uses a hard-coded method for finding game packets.
- This version adds auto-detection and support for VPNs/Ping Reducers. It also has been translated to English skills/spells and UI.

**Q: All names/my name shows as numbers?**
- Name detection can take a little time to work due to the game not sending names that often
- You can use a teleport scroll or teleport to Legion to try and get it to detect your name faster
- To save on teleports, if you use Exitlag, enable "Shortcut to restart all connections" option and use it to reload the game and populate names faster.

**Q: The UI appears, but no damage is shown.**  
- Verify Npcap installation  
- Exit the app, go to character select, then relaunch

**Q: I see DPS from others but not myself.**  
- DPS is calculated based on the monster with the highest total damage  
- Use the same training dummy as the player(s) already showing on the meter.

**Q: Contribution is not 100% while solo.**  
- Name capture may have failed

**Q: Some skills show as numbers.**  
- These are usually Theostones  
- Report others via GitHub Issues

---

## Download

👉 https://github.com/taengu/Aion2-Dps-Meter/releases

Please do not harass players based on DPS results.  
Use at your own risk.

---

## Community & Support

- 💬 **Join our Discord:** https://discord.gg/Aion2Global
- **Say thanks and fund new cool projects & features!**
  - ☕ [Buy me a Coffee](https://ko-fi.com/hiddencube)
  - ☕ [在爱发电支持我](https://afdian.com/a/hiddencube)
  - 🅿️ [Send with PayPal](https://www.paypal.me/taengoo)
  - 🎁 [Donate with Crypto](https://nowpayments.io/donation/thehiddencube)
  - **BTC**: `1GexKhgVZPYRqpfCKydXLoNUXRRRUoAUwT`
  - **ETH**: `0x38F0bc371A563A24eCa6034cFf77eB6173c7e3e7`
  - **USDC**: `0xA9571Fc95666350f6DFFB8Fb80ee27eE7db46b56`
