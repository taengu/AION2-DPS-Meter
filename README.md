# AION2Meter4J

아이온2 전투분석을 위한 미터기 프로젝트


[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![GitHub Issues](https://img.shields.io/github/issues/TK-open-public/Aion2-Dps-Meter)](https://github.com/TK-open-public/Aion2-Dps-Meter/issues)
[![GitHub Pull Requests](https://img.shields.io/github/issues-pr/TK-open-public/Aion2-Dps-Meter)](https://github.com/TK-open-public/Aion2-Dps-Meter/pulls)


## Table of Contents

- [빌드하기](#빌드하기)
- [사용법](#사용법)
- [UI설명](#ui-설명)
- [FAQ](#FAQ)
- [다운로드](#다운로드)



## 빌드하기

```bash
# 레포지토리 복사
git clone https://github.com/TK-open-public/Aion2-Dps-Meter.git

# 디렉토리 이동
cd Aion2-Dps-Meter

# msi 빌드
./gradlew packageDistributionForCurrentOS
```



## 사용법

1. npcap (https://npcap.com/#download) 를 설치합니다. (Install Npcap in WinPcap API-compatible Mode 필수 체크)
2. 해당 프로그램을 설치합니다 (1번과 순서 무관)
3. (권장) 캐릭터 선택창으로 이동합니다
4. 프로그램이 설치된 위치의 aion2meter4j.exe 를 관리자 권한으로 실행합니다.

![image](./readme-asset/firstUI.png)

5. 위의 UI가 출력된다면 성공적으로 실행되었습니다.



## UI 설명

![image](./readme-asset/uiDesc.png)

파란색 박스 - 몬스터의 이름이 출력되는 위치입니다 (예정)

갈색 박스 - 현재 쌓긴 기록을 모두 제거합니다

분홍색 박스 - 현재 DPS 기록을 올림차순/내림차순 정렬로 변경합니다

빨간색 박스 - 해당 플레이어의 직업 추론 성공시 직업 아이콘이 출력되는 위치입니다

주황색 박스 - 플레이어의 닉네임이 출력되는 위치입니다

초록색 박스 - 현재 DPS 산정중인 몹을 기준으로 DPS가 출력되는 위치입니다

보라색 박스 - 현재 DPS 산정중인 몹을 기준으로 데미지의 기여도가 출력되는 위치입니다




## FAQ

 - UI는 뜨는데 나나 다른사람의 데미지가 하나도 출력되지 않아요
    - npcap이 제대로 설치되었는지 확인해보세요
    - 미터기를 완전히 종료하고, 캐릭터 선택창에서 실행된것을 확인하고 월드에 접속해보세요
   
 - 허수아비를 치고있는데 제 dps 말고 제 옆사람 dps만 떠요
   - 현재 수집되는 데미지에서 가장 많은 데미지를 입은 몬스터를 기준으로 DPS를 표시합니다. 같은 허수아비를 치거나,레기온 허수아비 혹은 사나운 암굴 입구앞의 허수아비를 이용하세요
 
- 같은 허수아비치는데 치는사람 이름이 다 안떠요
  - 미터기가 캐릭터명 수집에 실패한 상태입니다. 캐릭터 선택창에서 미터기를 다시 실행해보세요

- 미터기엔 혼자 뜨는데 기여도가 100%가 아니에요
  - 미터기가 캐릭터명 수집에 실패하여 뜨지않고있는 기여자가 있을수있습니다.
  - 허수아비나 자힐이있는 일부 몬스터의 경우 힐을 데미지기여로 판단하고있는 버그가있습니다.(수정예정)


## 다운로드

### [이동](https://github.com/TK-open-public/Aion2-Dps-Meter/releases)