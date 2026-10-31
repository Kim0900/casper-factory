# GNSS Keeper v0.1.0

목적: Android 단말에서 **실제 GNSS 위치를 지속적으로 요청**하고 GNSS 상태를 CSV로 기록하는 최소 기능 앱입니다.

## 설계 원칙
- 카카오 T 기사 앱과 직접 통신하지 않음
- 접근성 서비스 사용 안 함
- 화면 캡처/자동 클릭/콜 감시 안 함
- Mock Location/좌표 위조 안 함
- Android `LocationManager`의 GPS provider와 공식 GNSS API만 사용
- 사용자가 화면에서 `GNSS 유지 · 기록 시작`을 눌러 foreground location service를 시작

## 기록 항목
세션마다 3개 파일을 생성합니다.

- `location.csv`: 1초 목표 위치, 정확도, 속도, 방위, 고도
- `gnss_status.csv`: 가시/사용 위성, L1/L5 유사 대역 위성 수, C/N0
- `gnss_measurements.csv`: raw GNSS measurement 이벤트 수, hardware clock discontinuity count

로그는 앱 전용 외부 저장소 `Android/data/kr.magi.gnsskeeper/files/gnss_logs/` 아래에 생성됩니다.

## S25+ 실기기 검증 절차
1. 위치 권한은 `정확한 위치`로 허용합니다.
2. 개발자 옵션 `강제 전체 GNSS 측정` 상태를 기록합니다.
3. 기준실험 A: 카카오내비 안전운전 + GNSS Keeper OFF.
4. 실험 B: 카카오내비 종료 + GNSS Keeper ON.
5. 같은 도로/비슷한 시간대에서 각 15~30분 운행합니다.
6. `accuracy`, `fix age`, 사용 위성수, L1/L5 수, `clock_discontinuity` 증가 패턴을 비교합니다.
7. B가 A와 동등하거나 더 안정적이면 카카오내비 상시 실행을 대체할 가능성이 높습니다.

## 빌드
Android Studio에서 프로젝트 폴더를 열고 Gradle 동기화 후 `app`을 빌드합니다.

- compileSdk: 36
- targetSdk: 36
- minSdk: 31
- AGP: 9.4.0
- Java 소스만 사용

## 현재 상태
- 소스 구현: 완료
- 로컬 Android SDK 빌드: 미검증 (현재 작업환경에 Android SDK가 없음)
- Galaxy S25+ 실기기 검증: 미실시
- 카카오 T 측 탐지 여부: 비공개 탐지 로직 때문에 절대 보장 불가. 앱은 카카오 T와의 직접 상호작용 기능을 의도적으로 배제함.


## 2026-10-31 안정성 보완 브랜치 (미배포)
- ACTIVE/PASSIVE 전환 시 기존 위치·GNSS 콜백 해제 후 새 모드 시작.
- 측정 콜백은 별도 HandlerThread에서 처리하고 알림 갱신은 최대 1Hz로 제한.
- Fix age는 실제 Location 객체의 elapsedRealtimeNanos 기준.
- CSV 초기화 실패는 시작 중단, CSV 기록 실패는 오류 노출 후 서비스 중단.
- 공개 저장소에 포함되어 있던 공유 디버그 서명키는 브랜치 최신 트리에서 제거.
  이전에 해당 인증서로 설치된 앱은 새 디버그 APK와 서명이 다를 수 있으므로
  저장된 로그를 백업한 뒤 앱을 제거하고 재설치해야 할 수 있습니다.
- Android 빌드: Gradle 9.6.0 / Android Gradle Plugin 9.4.0 / JDK17 / API36.
- 테스트 필요: 빌드 성공, S25+ 전환/중단/실제 GPS·위성 콜백,
  백그라운드 장기 실행, 45분 전력·발열, 파일 용량·시간정합성.
- 주의: GNSS Keeper의 정상 동작은 카카오 T 배차 개선을 보장하지 않습니다.
