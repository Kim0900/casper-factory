# v0.2.9 상태 대시보드 검증 범위

사용자가 요청한 파랑·녹색·노랑·빨강 상태 카드 6개를 추가한다. 색상과 상태 문구·접근성 설명을 함께 제공한다. 회색은 중지 또는 확인 불가이며 정상으로 간주하지 않는다.

| 항목 | 표시 기준 | 해석 제한 |
|---|---|---|
| 발열 | Android Thermal NONE=파랑, LIGHT=녹색, MODERATE=노랑, SEVERE/CRITICAL/EMERGENCY/SHUTDOWN=빨강. 빨강에서도 원래 단계 문구를 보존 | 온도계가 아닌 OS 열 제한 판정. 기기 제조사별 기준이 다르다. 배터리 온도는 별도 수치, CPU 온도가 아님 |
| 위치 | 기존 공통 PASSIVE 관찰 경로의 **측정 시각** 기준 5초 이내 녹색. ACTIVE 5초 초과~15초 노랑, 15초 초과 빨강. PASSIVE 무수신 파랑, 5초 초과 노랑 | 수신 시각으로 오래된 위치를 신선하게 포장하지 않는다. PASSIVE 무수신은 결함으로 판정하지 않음. 실제 위치 오차·GNSS 고정 여부·기사 앱 전달 여부·콜 성과 지표가 아님 |
| 실행 | RUNNING 녹색, STARTING/STOPPING 파랑, ERROR 빨강, STOPPED 회색 | 서비스 실행과 위치 실제 수신은 별개 |
| 인터넷 | 기본 네트워크에 INTERNET+VALIDATED 녹색, INTERNET만 있으면 노랑, 기본 네트워크 없음/INTERNET 없음 빨강, 판정 실패 회색 | OS의 일반 인터넷 판정. 카카오 서버 도달성, 신호 세기, 지연 시간은 측정하지 않음 |
| 절전 | Android 절전 켜짐 노랑, 꺼짐 녹색, 실패 회색. 배터리 잔량·전원 연결 보조 표시 | 삼성 앱별 절전/카카오 앱 배터리 제한 여부를 판정하지 않음 |
| 권한 | 이 앱 정확한 위치 허용+기기 위치 켜짐 녹색, 대략적 위치만 노랑, 권한 없음/기기 위치 꺼짐 빨강 | 다른 앱 권한 상태는 읽지 않음 |

화면이 보일 때 기존 1초 화면 갱신에 공개 OS 상태 읽기만 추가한다. 위치 요청·GNSS 설정·모드·기존 로그 스키마·내보내기 동작을 변경하지 않는다. 발열 색상에 따라 ACTIVE를 임의 중지하거나 전환하지 않는다. ACCESS_NETWORK_STATE 일반 권한만 추가하며 INTERNET 권한, 네트워크 탐침, 카카오 SDK 또는 외부 전송은 추가하지 않는다.

추가 시험: DashboardStatusTest 6건(열 상태 전 단계, PASSIVE 대기, 위치 시간 경계/시계 이상, 검증된 인터넷 조건, 정확한 권한, 실행/절전 구분). DashboardActivityTest 4건을 Android31/36에서 각각 실행(실제 Activity 열 상태 연결/색상/접근성, 실행과 위치 상태 분리 및 비요청, 오래된 측정값, 카드 전체 연결). 기존 자동시험 41건을 유지한다. 합계 예상 55건이며 실제 결과는 CI의 JUnit XML로 확정한다.

기존 S19 19건과 SR05 4건의 원본은 변경하지 않으며 새 통과 건수에 합산하지 않는다. 실물 Galaxy의 열 단계 변화·통신 단절/복구·절전 전환·폰트 확대·업데이트 설치 및 기록 보존은 실기기 확인 대상으로 남는다. 독립 재현 검증과 카카오콜 효과는 확인 전 성과로 보고하지 않는다.

참고: [PowerManager](https://developer.android.com/reference/android/os/PowerManager), [NetworkCapabilities](https://developer.android.com/reference/android/net/NetworkCapabilities), [BatteryManager](https://developer.android.com/reference/android/os/BatteryManager).
