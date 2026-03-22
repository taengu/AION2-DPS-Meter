[English](README.md) | [中文](README_ZH.md) | [한국어](README_KO.md)

# AION2 DPS Meter

**AION 2** 대만 및 한국 서버용 전투 분석(DPS 미터) 도구입니다.

저희의 목표는 서비스 이용약관을 위반할 수 있는 방식으로 게임에 간섭하는 방법에 의존하지 않는 커뮤니티 도구를 만드는 것입니다. 이 도구는 **무료**로 제공되며, [바로 설치할 수 있습니다.](#설치-방법)

참여를 원하시면 아래 링크의 Discord에서 연락해 주세요!

🔗 **GitHub 저장소:** https://github.com/taengu/Aion2-Dps-Meter
💬 **Discord (지원 및 커뮤니티): https://discord.gg/Aion2Global**

[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![GitHub Issues](https://img.shields.io/github/issues/taengu/Aion2-Dps-Meter)](https://github.com/taengu/Aion2-Dps-Meter/issues)
[![GitHub Pull Requests](https://img.shields.io/github/issues-pr/taengu/Aion2-Dps-Meter)](https://github.com/taengu/Aion2-Dps-Meter/pulls)

[Aion2-Dps-Meter](https://github.com/TK-open-public/Aion2-Dps-Meter)에서 포크되었습니다.

> **중요 공지**
> 이 프로젝트는 게임 운영사의 요청이 있거나, 패킷 암호화 또는 기타 대응 조치가 도입되거나, 사용을 금지하는 공식 성명이 발표될 경우 **중단되거나 비공개로 전환**됩니다.

> **복제품 및 사기에 주의하세요!**
> 이 프로젝트의 코드를 재포장한 다른 DPS 미터들이 유통되고 있으며, 일부는 돈을 받고 판매하고 일부는 소스 코드조차 공개하지 않습니다. 이 도구는 **무료이며 오픈소스**이고 앞으로도 그럴 것입니다. 유일한 공식 출처는 이 GitHub 저장소입니다. 누군가가 AION 2 DPS 미터를 판매하고 있다면, 사기일 수 있습니다.

---

## 설치 방법

### 1단계 — Npcap 설치 (필수)

https://npcap.com/#download 에서 **Npcap**을 다운로드하여 설치하세요.

> ⚠️ 설치 시 **"Install Npcap in WinPcap API-compatible Mode"**를 반드시 체크해야 합니다.
> 이 옵션을 활성화하지 않으면 미터가 작동하지 않습니다.

### 2단계 — 미터 다운로드 및 설치

👉 [Releases 페이지](https://github.com/taengu/Aion2-Dps-Meter/releases)에서 최신 설치 파일을 받으세요.

설치 프로그램을 실행하고 안내에 따라 진행하세요.

### 3단계 — 실행

바탕화면 바로가기 또는 시작 메뉴에서 **AION2 DPS Meter**를 실행합니다.

### 4단계 — Windows 방화벽 허용

처음 실행 시 Windows에서 네트워크 액세스를 요청합니다.
**허용**을 클릭하고, 프롬프트를 확장하여 **개인** 및 **공용** 네트워크를 모두 체크하면 데이터 누락을 방지할 수 있습니다.

### 문제 해결

미터에 데이터가 표시되지 않는 경우:
1. 미터 창의 **새로고침** 아이콘을 클릭합니다.
2. 그래도 안 되면 미터를 종료하고 다시 실행합니다.
3. 여전히 작동하지 않으면 게임에서 캐릭터 선택 화면으로 나간 후 미터를 재시작하고 다시 게임에 접속하세요.

---

## UI 설명

<img width="439" height="288" alt="image" src="https://github.com/user-attachments/assets/eae5dfd9-25c1-4e38-821f-6af0012acc93" />


- **파란 박스** – 몬스터 이름 표시 (예정)
- **갈색 박스** – 현재 전투 데이터 초기화
- **노란 박스** - DPS 또는 총 피해량 표시 전환
- **분홍 박스** – DPS 미터 확장 / 축소
- **빨간 박스** – 클래스 아이콘 (감지 시 표시)
- **주황 박스** – 플레이어 닉네임 (클릭하면 상세 정보)
- **하늘색 박스** – 현재 대상에 대한 DPS
- **보라 박스** – 기여도 비율
- **초록 박스** – 전투 타이머
  - 초록: 전투 중
  - 노란색: 피해 미감지 (일시 정지)
  - 회색: 전투 종료
- **검은 박스** - 플레이어 이름을 아직 검색 중일 때의 ID 표시

플레이어 행을 클릭하면 상세 통계가 열립니다.

> **적중 횟수**는 스킬 시전 횟수가 아니라 **실제 적중한 횟수**를 의미합니다.


## 빌드 방법
> ⚠️ **일반 사용자는 프로젝트를 빌드할 필요가 없습니다.**
> 이 섹션은 개발자 전용입니다.

```bash
# 저장소 클론
git clone https://github.com/taengu/Aion2-Dps-Meter.git

# 디렉토리 진입
cd Aion2-Dps-Meter

# IntelliJ 터미널에서 실행 (같은 창에서 출력 유지)
# IntelliJ가 별도의 cmd 창을 열면
# Settings > Build, Execution, Deployment > Gradle에서
# "Run in terminal"을 활성화하세요.
./gradlew run

# 배포 빌드 (Windows)
./gradlew packageDistributionForCurrentOS
```



---

## 자주 묻는 질문

**Q: 기존 미터와 무엇이 다른가요?**
- 기존 미터는 한국 서버용으로 작성되었으며 게임 패킷을 찾기 위해 하드코딩된 방법을 사용합니다.
- 이 버전은 자동 감지 기능과 VPN/핑 리듀서 지원을 추가했습니다. 또한 영어 스킬/주문 및 UI로 번역되었습니다.

**Q: 모든 이름/내 이름이 숫자로 표시됩니다.**
- 게임이 이름을 자주 전송하지 않기 때문에 이름 감지에 시간이 걸릴 수 있습니다.
- 텔레포트 스크롤을 사용하거나 군단으로 텔레포트하면 이름을 더 빨리 감지할 수 있습니다.
- 텔레포트를 절약하려면 Exitlag을 사용하는 경우 "Shortcut to restart all connections" 옵션을 활성화하고 이를 사용하여 게임을 다시 로드하면 이름이 더 빨리 표시됩니다.

**Q: UI는 나타나지만 피해량이 표시되지 않습니다.**
- Npcap 설치를 확인하세요.
- 앱을 종료하고 캐릭터 선택 화면으로 이동한 후 다시 실행하세요.

**Q: 다른 사람의 DPS는 보이지만 내 DPS는 보이지 않습니다.**
- DPS는 총 피해량이 가장 높은 몬스터를 기준으로 계산됩니다.
- 미터에 이미 표시되고 있는 플레이어와 같은 수련 인형을 사용하세요.

**Q: 솔로인데 기여도가 100%가 아닙니다.**
- 이름 캡처에 실패했을 수 있습니다.

**Q: 일부 스킬이 숫자로 표시됩니다.**
- 보통 테오스톤입니다.
- 다른 경우에는 GitHub Issues를 통해 보고해 주세요.

---

## 다운로드

👉 https://github.com/taengu/Aion2-Dps-Meter/releases

DPS 결과를 기반으로 다른 플레이어를 괴롭히지 마세요.
사용에 따른 책임은 본인에게 있습니다.

---

## 커뮤니티 및 지원

- 💬 **Discord 참여:** https://discord.gg/Aion2Global
- **감사를 표하고 새로운 프로젝트와 기능을 후원해 주세요!**
  - ☕ [커피 한 잔 사주기](https://ko-fi.com/hiddencube)
  - ☕ [在爱发电支持我](https://afdian.com/a/hiddencube)
  - 🅿️ [PayPal로 보내기](https://www.paypal.me/taengoo)
  - 🎁 [암호화폐로 기부하기](https://nowpayments.io/donation/thehiddencube)
  - **BTC**: `1GexKhgVZPYRqpfCKydXLoNUXRRRUoAUwT`
  - **ETH**: `0x38F0bc371A563A24eCa6034cFf77eB6173c7e3e7`
  - **USDC**: `0xA9571Fc95666350f6DFFB8Fb80ee27eE7db46b56`
