<details>
<summary>中文版更新说明</summary>

## 改进

**自动隐藏计量器**
- 当 Aion2 游戏窗口最小化或切换到其他应用时，计量器会自动隐藏；当游戏窗口恢复时，计量器会自动重新显示。使用快捷键手动隐藏的优先级高于自动隐藏

**多显示器支持**
- 计量器现在会自动显示在与游戏相同的屏幕上，适合使用多显示器的玩家

**应用内一键更新**
- 点击"立即安装"后，更新包会在后台下载并静默安装，完成后自动重启。下载过程中会显示进度条

**更可靠的玩家追踪**
- 当服务器重新分配玩家 ID（例如副本重置或队伍变动）时，计量器现在能自动跟踪新 ID，不再中断统计

**更智能的召唤物归属**
- 新增职业技能匹配机制：只有当召唤物的技能与主人的职业一致时，才会将其伤害归属到主人。避免了错误的召唤物关联

**详情面板自动刷新**
- 详情面板现在每 2 秒自动检查数据变化并刷新，无需手动关闭重开

**设置文件 UTF-8 支持**
- 设置文件现在使用 UTF-8 编码保存和读取，修复了中文/韩文昵称可能出现乱码的问题

**秒数显示本地化**
- DPS 计量器上的秒数显示现已支持本地化

## Bug 修复

- 修复了队伍成员变动后计量器停止更新的问题
- 修复了总伤害百分比计算中包含未被选中玩家伤害的问题
- 修复了重播模式下窗口标题检测不断轮询的问题
- 修复了昵称预填充在重播捕获时不生效的问题

</details>

<details>
<summary>한국어 릴리스 노트</summary>

## 개선 사항

**미터 자동 숨기기**
- Aion2 게임 창이 최소화되거나 다른 앱으로 전환되면 미터가 자동으로 숨겨지고, 게임 창이 복원되면 자동으로 다시 표시됩니다. 단축키로 수동 숨기기가 자동 숨기기보다 우선합니다

**다중 모니터 지원**
- 미터가 자동으로 게임과 같은 화면에 표시되어 다중 모니터 사용자에게 편리합니다

**앱 내 원클릭 업데이트**
- "지금 설치"를 클릭하면 업데이트가 백그라운드에서 다운로드 및 자동 설치되고, 완료 후 자동 재시작됩니다. 다운로드 진행률 표시 제공

**더 안정적인 플레이어 추적**
- 서버가 플레이어 ID를 재할당할 때(예: 던전 리셋 또는 파티 변경) 미터가 자동으로 새 ID를 추적하여 통계가 중단되지 않습니다

**더 똑똑한 소환수 귀속**
- 직업 스킬 매칭 메커니즘 추가: 소환수의 스킬이 주인의 직업과 일치할 때만 피해를 귀속합니다. 잘못된 소환수 연결을 방지합니다

**상세 패널 자동 새로고침**
- 상세 패널이 2초마다 데이터 변경을 확인하고 자동으로 갱신됩니다

**설정 파일 UTF-8 지원**
- 설정 파일이 UTF-8 인코딩으로 저장 및 읽기되어 한국어/중국어 닉네임이 깨지는 문제를 해결했습니다

**초 표시 현지화**
- DPS 미터의 초 표시가 이제 현지화를 지원합니다

## 버그 수정

- 파티 구성 변경 후 미터가 업데이트를 멈추는 문제 수정
- 선택되지 않은 플레이어의 피해가 총 피해 백분율 계산에 포함되던 문제 수정
- 리플레이 모드에서 창 제목 감지가 계속 폴링하던 문제 수정
- 리플레이 캡처 시 닉네임 미리 채우기가 작동하지 않던 문제 수정

</details>

---

## Improvements

**Auto-hide meter**
- The meter now automatically hides when the Aion2 game window is minimized or you switch to another app, and reappears when the game window is restored. Manual hotkey hiding takes priority over auto-hide

**Multi-monitor support**
- The meter now automatically appears on the same screen as the game, making it convenient for multi-monitor setups

**In-app one-click update**
- Clicking "Install Now" downloads and silently installs the update in the background, then auto-restarts. A download progress bar is shown during the process

**More reliable player tracking**
- When the server reassigns your player ID (e.g. dungeon reset or party change), the meter now automatically follows the new ID without interrupting your stats

**Smarter summon attribution**
- Added job skill matching: summon damage is only attributed to an owner when the summon's skills match the owner's class. This prevents incorrect summon associations

**Details panel auto-refresh**
- The Details panel now checks for data changes every 2 seconds and refreshes automatically

**Settings file UTF-8 support**
- Settings are now saved and loaded in UTF-8, fixing garbled CJK (Chinese/Korean) nicknames in settings

**Seconds display localized**
- The seconds display on the DPS meter is now localized

## Bug Fixes

- Fixed the meter stopping after party composition changes
- Fixed total damage percentage calculations including damage from unselected players
- Fixed replay mode continuously polling for window title
- Fixed nickname pre-fill not working for replay captures
