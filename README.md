# AudioFlow 1.7.0

AudioFlow는 Google 계정 없이 공개 YouTube 콘텐츠의 오디오를 듣는 개인용 Android 앱입니다. Android 8.0(API 26) 이상을 대상으로 합니다. 검색과 스트리밍에는 인터넷 연결이 필요합니다. 즐겨찾기, 재생 기록, 위치, 플레이리스트, 대기열 및 설정은 기기에 저장됩니다.

## 설치와 사용

1. [GitHub 릴리스의 개인 설치용 APK](https://github.com/LibraLightLuda/AudioFlow/releases/download/v1.7.0/AudioFlow-1.7.0.apk)를 받아 휴대전화에서 엽니다. 기존 버전이 설치돼 있으면 업데이트합니다. USB 디버깅을 사용한다면 APK를 내려받은 폴더에서 `adb install -r AudioFlow-1.7.0.apk`를 실행하세요.
2. 홈의 **노래** 검색은 YouTube Music과 일반 동영상 검색 결과를 합쳐 관련 공식 뮤직비디오·음원을 먼저 표시합니다. **전체**와 **채널** 필터도 사용할 수 있습니다. YouTube에서 영상의 **공유 → AudioFlow**를 선택하면 해당 영상이 바로 재생됩니다.
3. 채널을 열면 공개 동영상·뮤직비디오, 플레이리스트, Shorts 중 채널이 제공하는 목록을 볼 수 있습니다. 목록 끝의 **더 보기**로 다음 항목을 불러오고, 공개 플레이리스트를 열어 **내 플레이리스트로 저장**하면 로컬 복사본이 생깁니다.
4. 검색 결과에서 골라 재생하거나 공유로 바로 재생한 곡은 라이브러리의 **기본 플레이리스트**에 자동 추가됩니다. 곡 옵션의 **YouTube에서 채널 열기**는 YouTube 앱을 우선 사용하고, 설치되지 않았다면 브라우저에서 엽니다.
5. 기본·일반 플레이리스트와 재생 기록에서 항목을 왼쪽으로 밀거나 옵션에서 제외할 수 있습니다. **모든 목록·기록에서 제외**는 확인 후 같은 영상의 모든 목록 항목과 재생 기록을 지웁니다. **중복 정리**는 해당 목록에 표시된 제목·아티스트와 길이를 비교해 후보를 보여줍니다.
6. 전체 플레이어의 **가사·자막**은 업로더가 공개 자막을 제공하는 영상에서 현재 재생 구간의 자막을 표시합니다. 한국어, 영어, 그 밖의 제공 언어 순으로 선택합니다.
7. **재생 종료**는 현재 재생과 취침 타이머를 종료하고 대기열과 위치를 저장합니다. 앱을 다시 열면 대기열을 복원하고 재생 버튼을 기다립니다.
8. **다음에 재생**은 현재 곡 바로 뒤에 넣습니다. 필요한 경우 셔플과 한 곡 반복을 끕니다. 대기열에서 손잡이를 길게 눌러 순서를 바꾸거나, 여러 곡을 제외하거나, 대기열을 새 플레이리스트로 저장할 수 있습니다. 홈의 **이어서 듣기**로 저장된 재생 위치를 이어갑니다.
9. 검색 결과에서 **공식 영상·음원 우선**과 **Shorts 제외**를 조합할 수 있습니다. 공식 표시는 제목과 채널 표기를 참고합니다. 플레이리스트 이름 변경, 목록 내 검색·정렬, 여러 곡 제외는 라이브러리에서 사용할 수 있습니다.
10. 설정에서 즐겨찾기와 플레이리스트를 JSON으로 내보내거나 병합할 수 있습니다. 가져오기 전에 추가·중복 수를 표시합니다. 기존 곡 정보와 목록 순서를 보존하며 재생 기록, 대기열, 계정 정보, 스트림 URL은 백업에 포함하지 않습니다.
11. **다음 곡 미리 준비**와 **관련 곡 이어 재생**은 설정에서 각각 켭니다. 다음 곡 준비는 데이터 사용량이 제한되지 않은 연결에서 현재 버퍼가 충분할 때 동작합니다. 관련 곡은 같은 목록이나 채널에서 아직 듣지 않은 곡을 선택합니다.
12. 재생이 실패하면 현재 곡과 위치를 유지한 채 재시도하거나 다음 곡을 재생하거나 YouTube에서 열 수 있습니다. **가사·자막**에서 현재 줄을 강조하고 시간 표시가 있는 줄을 눌러 이동할 수 있습니다.

## 음향 설정

**전체 플레이어 → 음향 설정** 또는 **설정 → 음향 설정**에서 재생 중 조절할 수 있습니다. 이퀄라이저는 80 Hz, 250 Hz, 1 kHz, 4 kHz, 12 kHz를 각각 −9에서 +9 dB까지 조절합니다. 원음, 저음 강조, 보컬 선명, 록, 재즈, 클래식, 팟캐스트, 넓은 스테레오, 헤드폰 공간감의 기본 프리셋 9개와 사용자 프리셋 최대 12개를 지원합니다.

- **스테레오 확장**은 좌우 차이를 조절합니다. **헤드폰 공간감**은 좌우에 짧은 지연음과 필터를 섞습니다. 두 효과 모두 스테레오 신호에서 강도를 조절할 수 있습니다.
- **기기 공간 음향**은 Android의 공간 음향 설정을 사용합니다. 실제 공간 처리는 지원 기기, 현재 출력 장치와 콘텐츠 형식에 따라 달라집니다. Android 12L 이상에서는 현재 출력의 스테레오 처리 가능 여부를 표시합니다. [Android 공간 음향 문서](https://developer.android.com/media/grow/spatial-audio)
- 효과는 AudioFlow의 Media3 재생 경로에 적용합니다. 프리셋과 마지막 음향 설정은 기기에 저장합니다. 부스트나 공간 효과를 켜면 과도한 출력 피크를 줄이기 위해 전체 레벨을 낮출 수 있습니다.

5밴드 EQ는 Robert Bristow-Johnson의 공개 피킹 필터 공식을 적용했습니다. 공간 효과 설계에서는 FFmpeg의 스테레오 확장 필터와 RootlessJamesDSP를 검토했습니다. 앱의 PCM 처리는 Media3 오디오 프로세서에서 Kotlin으로 실행합니다. [Audio EQ Cookbook](https://www.w3.org/TR/audio-eq-cookbook/) · [FFmpeg stereowiden](https://github.com/FFmpeg/FFmpeg/blob/master/libavfilter/af_stereowiden.c) · [RootlessJamesDSP](https://github.com/timschneeb/RootlessJamesDSP)

## AudioFlow 라디오

홈의 **라디오 시작**을 누르면 곡을 지정하지 않아도 추천곡을 재생합니다. 즐겨찾기와 최근 재생에서 자주 듣는 채널과 제목 단어를 찾고, 기록이 없으면 K-pop·인디·팝·록 등 여러 장르의 공개 검색으로 시작합니다. 다음 곡이 두 곡 이하로 남으면 대기열을 채우고 지나간 항목을 정리합니다.

후보의 관련성과 다양성을 함께 고려하는 **MMR 재정렬**을 Kotlin으로 적용했습니다. 같은 곡의 제목 변형과 최근에 들은 곡을 제외하고, 같은 채널이나 제목이 비슷한 곡이 이어질 가능성을 낮춥니다. 모델 학습 서버 없이 동작합니다. [MMR 원 논문](https://www.cs.cmu.edu/afs/cs/Web/People/jgc/publication/MMR_DiversityBased_Reranking_SIGIR_1998.pdf) · [추천 후보 생성과 재정렬](https://developers.google.com/machine-learning/recommendation/dnn/re-ranking)

공개 프로젝트 중 [Recommenders](https://github.com/recommenders-team/recommenders), [LightFM](https://github.com/lyst/lightfm), [Spotify Annoy](https://github.com/spotify/annoy)를 검토했습니다. 협업 필터링 모델에는 여러 사용자의 상호작용 데이터가 필요하고, Annoy는 벡터 검색용입니다. AudioFlow는 소규모 후보를 바로 비교할 수 있어 MMR을 선택했습니다.

점수 계산은 기기 안에서 수행합니다. 공개 YouTube에 보내는 검색어는 장르 또는 자주 듣는 공개 채널의 이름입니다. 라디오를 끄면 시작 전 대기열과 재생 위치가 복원됩니다. 요청 제한이 걸리면 자동 검색을 멈추고, 후보가 없거나 일시적으로 검색이 실패하면 한 번 기다렸다 다시 시도합니다.

## 앱 업데이트

1. 1.3.0 이하를 사용 중이면 위 APK를 한 번 설치합니다. 이후 **설정 → 앱 업데이트 → 최신 버전 확인**에서 설치된 버전과 GitHub의 최신 정식 릴리스를 비교할 수 있습니다. [GitHub 배포처](https://github.com/LibraLightLuda/AudioFlow/releases/latest)
2. 새 버전이 나오면 **다운로드 및 설치**를 누릅니다. 앱은 APK의 크기와 SHA-256, 패키지명, 버전 코드 및 설치된 앱과의 서명 일치를 확인합니다. Android에서 AudioFlow의 **이 출처 허용**을 요구하면 허용하고 돌아온 뒤 시스템 설치 화면에서 업데이트를 확인합니다.
3. 앱 내부 설치가 어려우면 **GitHub 릴리스 열기**에서 APK를 직접 받을 수 있습니다. 앱의 업데이트 확인은 사용자가 버튼을 눌렀을 때만 실행됩니다.
4. 업데이트 전에는 GitHub 릴리스의 변경사항을 미리 볼 수 있습니다. 업데이트 후 첫 실행에서는 APK에 포함된 변경사항을 한 번 표시합니다. 확인한 내용은 **설정 → 앱 업데이트 → 이번 버전 변경사항**에서 다시 볼 수 있습니다.

새 릴리스를 준비할 때 [앱에 넣는 변경사항](app/src/main/assets/whats-new.md)의 첫 제목과 내용을 새 버전에 맞추고, 같은 내용을 GitHub 릴리스에 사용합니다. 빌드는 제목과 앱 버전이 일치하는지 검사합니다.

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

- 1.2.1은 검색·재생·자막·화면/알림 이미지의 HTTP 요청에 쿠키·인증 헤더 제거, 목적별 HTTPS 호스트 제한, 이동 전 리디렉션 검사를 적용합니다. 민감한 오류 로그와 HTTP 디스크 캐시도 줄였습니다. IP 및 요청 방식은 YouTube에 보이며 익명성이나 제재 방지를 보장하지 않습니다. [보호 범위와 남는 한계](SECURITY.md)
- Kotlin, Compose Material 3, Media3 1.9.2, Room, DataStore, Coroutines/Flow를 사용합니다. 콘텐츠 조회는 `Catalog` 인터페이스와 NewPipeExtractor 어댑터로 분리했습니다.
- 오디오 전용 HTTP 스트림을 우선 사용합니다. 오디오 전용 스트림이 없는 경우 지원되는 결합 스트림에서 소리만 재생하므로 영상 데이터도 전송될 수 있습니다.
- 일반 공개 VOD를 지원합니다. 라이브 방송, 공개 예정 콘텐츠, DRM, 구매·멤버십·로그인·연령 확인이 필요한 콘텐츠는 지원 범위 밖입니다. 공개 플레이리스트 한 번의 조회는 최대 100개이고, 재생 대기열 한 번의 구성은 최대 200개입니다.
- 스트림 요청이 HTTP 403 또는 410으로 실패하면 스트림 주소를 한 번 다시 조회합니다. 요청 제한과 접근 거부는 화면에 표시합니다. 서명된 스트림 URL은 기기에 저장하지 않습니다.
- Media3 재생 버퍼의 RAM 목표는 8 MiB입니다. 임시 스트림 캐시는 최대 24 MiB이며 오래 사용하지 않은 조각부터 비웁니다. 연결 오류나 일시적인 서버 오류에는 최대 4회 재시도합니다. 끊김을 버틸 수 있는 시간은 미리 받은 데이터와 음원 비트레이트에 따라 달라집니다.
- 비공식 조회 방식은 YouTube 이용약관과 충돌할 수 있고, YouTube의 응답 형식이나 익명 접근 정책이 변경되면 검색 또는 재생이 중단될 수 있습니다. [YouTube 이용약관](https://www.youtube.com/t/terms)

## 라이브러리와 출처

버전은 [앱의 Gradle 설정](app/build.gradle)을 기준으로 기록했습니다. OkHttp는 Media3와 Coil을 통해 들어오며 릴리스 빌드에서 4.12.0이 선택됩니다.

| 라이브러리 | AudioFlow에서 사용하는 부분 | 공식 소스 |
| --- | --- | --- |
| NewPipeExtractor v0.27.0 | 검색, 영상·채널·플레이리스트 조회, 공개 스트림과 자막 정보 추출. `com.github.TeamNewPipe:NewPipeExtractor:v0.27.0`을 [JitPack](https://jitpack.io)에서 받습니다. | [TeamNewPipe/NewPipeExtractor v0.27.0](https://github.com/TeamNewPipe/NewPipeExtractor/releases/tag/v0.27.0) |
| AndroidX Media3 1.9.2 | ExoPlayer 재생, MediaSessionService, 미디어 캐시, OkHttp 데이터 소스. 직접 의존성은 `media3-exoplayer`, `media3-session`, `media3-datasource-okhttp`입니다. | [androidx/media 1.9.2](https://github.com/androidx/media/releases/tag/1.9.2) |
| OkHttp 4.12.0 | 검색, 재생, 자막, 이미지의 HTTP 연결과 요청 검증. | [OkHttp 4.12.0 소스](https://github.com/lysine-dev/okhttp/tree/parent-4.12.0) |
| Coil Compose 2.7.0 | Compose 화면의 썸네일 로딩. | [coil-kt/coil 2.7.0](https://github.com/coil-kt/coil/releases/tag/2.7.0) |
| Jetpack Compose BOM 2025.12.00, Material 3, Activity Compose 1.12.2, Lifecycle Runtime Compose 2.10.0 | 앱 화면, 아이콘, Activity 연결, 수명주기에 따른 상태 구독. | [AndroidX 소스](https://github.com/androidx/androidx) |
| Room 2.8.4, DataStore Preferences 1.2.0 | 기록, 즐겨찾기와 플레이리스트를 Room에, 대기열과 앱 설정을 DataStore에 저장. | [AndroidX 소스](https://github.com/androidx/androidx) |
| kotlinx-coroutines-android 1.10.2 | 비동기 조회, 재생 상태와 로컬 데이터의 Flow 처리. | [Kotlin/kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) |
| KSP 2.2.21-2.0.4, desugar_jdk_libs_nio 2.1.5 | Room 코드 생성과 낮은 Android 버전에서의 Java API 호환. | [KSP](https://github.com/google/ksp), [Desugar JDK Libraries](https://github.com/google/desugar_jdk_libs) |
| JUnit 4.13.2, AndroidX Test JUnit 1.3.0 및 Runner 1.7.0 | JVM 단위 테스트와 Android 기기 테스트. | [JUnit 4](https://github.com/junit-team/junit4), [AndroidX Test](https://github.com/android/android-test) |

콘텐츠 조회는 [Catalog.kt](app/src/main/java/app/audioflow/Catalog.kt)에서 NewPipeExtractor를 호출합니다. 재생과 네트워크 보호를 구현할 때 참고한 Android 공식 문서는 [Media3 네트워크 스택](https://developer.android.com/media/media3/exoplayer/network-stacks), [네트워크 보안 구성](https://developer.android.com/privacy-and-security/security-config), [알림 이미지 로더](https://developer.android.com/reference/kotlin/androidx/media3/datasource/DataSourceBitmapLoader)입니다. 적용 범위는 [SECURITY.md](SECURITY.md)에 기록했습니다.

AudioFlow는 GPL-3.0-or-later로 배포합니다. [AudioFlow 라이선스](LICENSE)와 [NewPipeExtractor 라이선스](https://github.com/TeamNewPipe/NewPipeExtractor/blob/v0.27.0/LICENSE)를 확인할 수 있습니다.
