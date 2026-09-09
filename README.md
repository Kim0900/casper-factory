# casper-factory

캐스퍼가 만드는 여러 앱을 담는 저장소입니다. 앱마다 폴더 하나씩
분리되어 있고, 각 폴더에 코드가 push되면 그 앱만 자동으로 빌드됩니다
(GitHub Actions, `.github/workflows/build-<앱이름>.yml`).

## 담긴 프로젝트

### GNSSKeeper
Android GNSS 위치 정확도 진단 앱. 카카오T 기사앱과 직접 상호작용하지
않고, Android 표준 위치 API만으로 GNSS 상태를 기록합니다.
상세: [`GNSSKeeper/README.md`](./GNSSKeeper/README.md)

## 새 앱 추가하는 법 (캐스퍼용 메모)
1. 저장소 루트에 새 폴더(`앱이름/`) 생성, 그 안에 프로젝트 전체 배치
2. `.github/workflows/build-<앱이름>.yml` 신설, `paths` 필터를 그
   폴더로 한정
3. Android 앱이면 `<앱이름>/keystore/`에 고정 서명키를 두고
   `app/build.gradle.kts`의 `signingConfigs.debug`에서 참조 —
   빌드마다 서명이 바뀌면 기존 설치본 위에 업데이트가 안 되고
   재설치해야 하므로 반드시 고정 키 사용

## 빌드 결과 받는 법
1. https://github.com/Kim0900/casper-factory/actions 접속
2. 원하는 워크플로우의 최신 성공(초록 체크) 실행 클릭
3. 하단 Artifacts에서 다운로드 → 압축 풀면 `.apk` 등 결과물
