# AudioFlow 1.2.0

AudioFlow는 Google 계정 없이 공개 YouTube 콘텐츠의 오디오를 듣는 개인용 Android 앱입니다. Android 8.0(API 26) 이상을 대상으로 합니다. 검색과 스트리밍에는 인터넷 연결이 필요합니다. 즐겨찾기, 재생 기록, 위치, 플레이리스트, 대기열 및 설정은 기기에 저장됩니다.

## 설치와 사용

1. [GitHub 릴리스의 개인 설치용 APK](https://github.com/LibraLightLuda/AudioFlow/releases/download/v1.2.0/AudioFlow-1.2.0.apk)를 받아 휴대전화에서 엽니다. 기존 버전이 설치돼 있으면 업데이트합니다. USB 디버깅을 사용한다면 APK를 내려받은 폴더에서 `adb install -r AudioFlow-1.2.0.apk`를 실행하세요.
2. 홈의 **노래** 검색은 YouTube Music과 일반 동영상 검색 결과를 합쳐 관련 공식 뮤직비디오·음원을 먼저 표시합니다. **전체**와 **채널** 필터도 사용할 수 있습니다. YouTube에서 영상의 **공유 → AudioFlow**를 선택하면 해당 영상이 바로 재생됩니다.
3. 채널을 열면 공개 동영상·뮤직비디오, 플레이리스트, Shorts 중 채널이 제공하는 목록을 볼 수 있습니다. 목록 끝의 **더 보기**로 다음 항목을 불러오고, 공개 플레이리스트를 열어 **내 플레이리스트로 저장**하면 로컬 복사본이 생깁니다.
4. 검색 결과에서 골라 재생하거나 공유로 바로 재생한 곡은 라이브러리의 **기본 플레이리스트**에 자동 추가됩니다. 곡 옵션의 **YouTube에서 채널 열기**는 YouTube 앱을 우선 사용하고, 설치되지 않았다면 브라우저에서 엽니다.
5. 기본·일반 플레이리스트와 재생 기록에서 항목을 왼쪽으로 밀거나 옵션에서 제외할 수 있습니다. **모든 목록·기록에서 제외**는 확인 후 같은 영상의 모든 목록 항목과 재생 기록을 지웁니다. **중복 정리**는 해당 목록에 표시된 제목·아티스트와 길이를 비교해 후보를 보여줍니다.
6. 전체 플레이어의 **가사·자막**은 업로더가 공개 자막을 제공하는 영상에서 현재 재생 구간의 자막을 표시합니다. 한국어, 영어, 그 밖의 제공 언어 순으로 선택합니다.
7. **재생 종료**는 현재 재생과 취침 타이머를 종료하고 대기열과 위치를 저장합니다. 앱을 다시 열면 대기열을 복원하고 재생 버튼을 기다립니다.

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
- Media3 재생 버퍼의 RAM 목표는 8 MiB입니다. 임시 스트림 캐시는 최대 24 MiB이며 오래 사용하지 않은 조각부터 비웁니다. 연결 오류나 일시적인 서버 오류에는 최대 4회 재시도합니다. 끊김을 버틸 수 있는 시간은 미리 받은 데이터와 음원 비트레이트에 따라 달라집니다.
- 비공식 조회 방식은 YouTube 이용약관과 충돌할 수 있고, YouTube의 응답 형식이나 익명 접근 정책이 변경되면 검색 또는 재생이 중단될 수 있습니다. [YouTube 이용약관](https://www.youtube.com/t/terms)
