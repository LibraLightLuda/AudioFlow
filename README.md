# AudioFlow 1.1.0

AudioFlow는 Google 계정 없이 공개 YouTube 콘텐츠의 오디오를 듣는 개인용 Android 앱입니다. Android 8.0(API 26) 이상을 대상으로 합니다. 검색과 스트리밍에는 인터넷 연결이 필요합니다. 즐겨찾기, 재생 기록, 위치, 플레이리스트, 대기열 및 설정은 기기에 저장됩니다.

## 설치와 사용

1. [GitHub 릴리스의 개인 설치용 APK](https://github.com/LibraLightLuda/AudioFlow/releases/download/v1.1.0/AudioFlow-1.1.0.apk)를 받아 휴대전화에서 엽니다. 설치 화면에서 요청하면 APK를 연 앱에 **이 출처 허용** 권한을 부여합니다. USB 디버깅을 사용한다면 APK를 내려받은 폴더에서 `adb install -r AudioFlow-1.1.0.apk`를 실행하세요.
2. 홈에서 곡 제목, 유튜버 이름, `@핸들` 또는 YouTube 링크를 입력합니다. **채널** 필터는 유튜버 이름으로 찾을 때 채널만 표시합니다. YouTube 또는 브라우저의 **공유** 메뉴에서도 AudioFlow로 링크를 보낼 수 있습니다.
3. 채널을 열면 공개 동영상·뮤직비디오, 플레이리스트, Shorts 중 채널이 제공하는 목록을 볼 수 있습니다. 목록 끝의 **더 보기**로 다음 항목을 불러오고, 공개 플레이리스트를 열어 **내 플레이리스트로 저장**하면 로컬 복사본이 생깁니다.
4. 트랙을 누르면 재생하고, 트랙 옵션에서 대기열, 즐겨찾기, 로컬 플레이리스트에 추가할 수 있습니다. 하단 미니 플레이어를 누르면 탐색, 반복, 셔플, 속도, 취침 타이머와 대기열을 볼 수 있습니다.
5. 홈 **최근 재생**과 라이브러리 **재생 기록**에서 항목을 왼쪽으로 밀거나 옵션의 **재생 기록에서 삭제**를 누르면 해당 기록과 트랙별 재생 위치를 지웁니다.
6. **재생 종료**는 현재 재생과 취침 타이머를 종료하고 대기열과 위치를 저장합니다. 앱을 다시 열면 대기열을 복원하고 재생 버튼을 기다립니다.

이 Release APK는 이 PC의 Android 개발용 키로 서명했습니다. 같은 패키지를 데이터 유지 상태에서 업데이트하려면 같은 서명 키가 필요합니다.

## 빌드와 검증

JDK 17, Android SDK Platform 36 및 Build Tools 36.0.0을 설치하고 `ANDROID_HOME`을 설정하거나 Android Studio에서 이 프로젝트를 여세요. PowerShell에서 아래 명령을 실행합니다.

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease lintRelease
$env:AUDIOFLOW_LIVE = '1'
.\gradlew.bat testDebugUnitTest --rerun-tasks
```

`assembleDebug`는 서명된 개발용 APK(`app.audioflow.debug`)를 만듭니다. `assembleRelease`의 기본 출력은 서명 전 APK입니다. 테스트 빌드와 개인 설치용 빌드의 로컬 데이터는 서로 분리됩니다. [검증 결과와 기기에서 확인할 항목](VERIFICATION.md)을 참고하세요.

## 구현 및 제약

- Kotlin, Compose Material 3, Media3 1.9.2, Room, DataStore, Coroutines/Flow를 사용합니다. 콘텐츠 조회는 `Catalog` 인터페이스와 NewPipeExtractor 어댑터로 분리했습니다.
- [NewPipeExtractor v0.27.0](https://github.com/TeamNewPipe/NewPipeExtractor/releases/tag/v0.27.0)을 고정했습니다. 해당 버전은 Java 17을 사용하며 GPL-3.0-or-later입니다. 이 프로젝트의 소스도 GPL-3.0-or-later로 제공합니다. [라이선스 전문](LICENSE)
- 오디오 전용 HTTP 스트림을 우선 사용합니다. 오디오 전용 스트림이 없는 경우 지원되는 결합 스트림에서 소리만 재생하므로 영상 데이터도 전송될 수 있습니다.
- 일반 공개 VOD를 지원합니다. 라이브 방송, 공개 예정 콘텐츠, DRM, 구매·멤버십·로그인·연령 확인이 필요한 콘텐츠는 지원 범위 밖입니다. 공개 플레이리스트 한 번의 조회는 최대 100개이고, 재생 대기열 한 번의 구성은 최대 200개입니다.
- 스트림 요청이 HTTP 403 또는 410으로 실패하면 스트림 주소를 한 번 다시 조회합니다. 요청 제한과 접근 거부는 화면에 표시합니다. 서명된 스트림 URL은 기기에 저장하지 않습니다.
- 비공식 조회 방식은 YouTube 이용약관과 충돌할 수 있고, YouTube의 응답 형식이나 익명 접근 정책이 변경되면 검색 또는 재생이 중단될 수 있습니다. [YouTube 이용약관](https://www.youtube.com/t/terms)
