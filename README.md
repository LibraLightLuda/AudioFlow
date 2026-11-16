# AudioFlow 1.11.0

업데이트 자동 확인, 다섯 가지 색 테마, 항목별 설정 화면을 제공합니다. 음향 처리기가 Media3의 빈 입력 버퍼를 자기 자신에게 복사해 재생을 중단시키던 오류도 수정했습니다. 연결된 실제 휴대전화에서의 YouTube 재생·잠금 화면·블루투스 동작은 별도 검증이 필요합니다.

AudioFlow는 Google 계정 없이 공개 YouTube 콘텐츠의 오디오를 듣는 개인용 Android 앱입니다. Android 8.0(API 26) 이상을 대상으로 합니다. 검색과 스트리밍에는 인터넷 연결이 필요합니다. 즐겨찾기, 재생 기록, 위치, 플레이리스트, 대기열 및 설정은 기기에 저장됩니다.

## 설치와 사용

1. [GitHub 릴리스의 개인 설치용 APK](https://github.com/LibraLightLuda/AudioFlow/releases/download/v1.11.0/AudioFlow-1.11.0.apk)를 받아 휴대전화에서 엽니다. 기존 버전이 설치돼 있으면 업데이트합니다. USB 디버깅을 사용한다면 APK를 내려받은 폴더에서 `adb install -r AudioFlow-1.11.0.apk`를 실행하세요.
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


## MP3 음악 파일 저장

1.11.0부터 즐겨찾기·기록·플레이리스트의 **음악 목록 더보기 → 목록 모두 MP3로 저장**, 플레이어의 **대기열 모두 MP3로 저장**으로 한 번에 최대 5,000곡을 폴더에 순차 저장합니다. 검색어로 필터링한 화면에서도 목록 전체를 대상으로 합니다. 저장된 ID의 파일이 실제로 존재하거나, 선택한 폴더에 같은 ID 표식·기존 파일명이 있으면 변환 전에 건너뜁니다. 결과에 제목과 중복 파일명을 표시하며, 파일명 충돌을 동일한 녹음이라는 증거로 사용하지 않습니다. 새 일괄 파일명에는 `[YouTube ID]`를 넣습니다.

**설정 → 음악 파일 저장**에서 저장 폴더를 지정하고 **완청한 곡 자동 저장**을 켤 수 있습니다(기본 꺼짐). 처음부터 끝까지 실제 진행된 곡만 대상이며, 일시정지는 허용하고 탐색·건너뛰기·중간 위치 복원은 제외합니다. 폴더 권한과 자동 저장 설정은 이 기기에만 적용하므로 백업에 포함하지 않습니다. 폴더를 바꾸면 자동 저장을 다시 켜야 합니다. 모바일 데이터·CPU·배터리·저장 공간을 사용합니다.

같은 설정의 **저장 파일 중복 정리**는 선택 폴더의 완료된 MP3만 대상으로 파일 크기와 SHA-256이 같은 파일을 찾습니다. 보관할 파일 하나를 선택하고 삭제를 확인한 후, 삭제 직전 내용을 다시 비교합니다. 다른 음질·태그·버전의 파일을 같은 노래로 추측해 삭제하지 않습니다. 삭제된 파일에 연결된 저장 기록은 보관 파일로 옮깁니다. 기존 라이브러리의 제목 기반 중복 후보 정리와 별도입니다.

저장은 Android WorkManager 2.11.2의 영속 순차 대기열을 사용합니다. 100곡 단위로 연결하고 취소는 남은 대기열에도 적용합니다. 곡별 30분·작업 단위 45분·원본 512MiB·결과 256MiB를 제한하며, 중단된 목록은 결과를 확인한 뒤 다시 요청할 수 있습니다. 403/429는 30분 쉬고, 네트워크 오류는 해당 목록의 남은 요청을 중단합니다. 결과는 곡별로 최근 10개 목록까지 보관합니다. Android의 백그라운드·배터리·작업 할당량 때문에 자동 저장이 지연될 수 있으며 즉시 완료를 보장하지 않습니다. [공식 WorkManager 설명](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)

앱 시작 시 최신 정식 릴리스를 확인하고 현재보다 새 버전이면 버전마다 **한 번만 앱 내 알림**을 표시합니다. 같은 버전으로 재실행·회전해도 다시 표시하지 않으며, 더 높은 새 버전에서는 다시 한 번 표시합니다. 알림의 **업데이트**로 앱 업데이트 화면을 엽니다. 앱 데이터 삭제·재설치로 알림 기록을 지우면 다시 표시될 수 있습니다.

곡의 더보기 또는 전체 플레이어 더보기 → **MP3 파일로 저장** → **저장 위치 선택**에서 기기의 Music/Downloads 등 원하는 폴더를 선택합니다. Android 13 이상에서 알림 권한은 진행·취소 알림을 위한 선택 사항이며, 거절해도 앱 안에서 저장할 수 있습니다. 다른 앱 사용·화면 잠금 중에도 포그라운드 작업으로 계속합니다. 곡 메뉴에서 **진행 상황 보기**를 선택하면 진행창을 다시 열 수 있습니다.

- 기본 확장자는 **`.mp3`**, MIME은 `audio/mpeg`입니다. Android MediaExtractor/MediaCodec으로 디코딩한 PCM을 [Java LAME 3.98.4](https://github.com/nwaldispuehl/java-lame/releases/tag/v3.98.4)로 실제 변환합니다. 표준 CBR 192 kbps를 기준으로 하며 32 kHz 미만 입력은 128 kbps를 사용합니다. 손실 변환이므로 원본보다 음질이 좋아지지 않으며 EQ·배속·공간 효과는 저장 파일에 적용하지 않습니다.
- Java LAME는 무료 오픈소스 **LGPL-3.0**입니다. [출처·해시·라이선스·전체 원본 소스](third_party/java-lame)와 앱 자산의 라이선스 고지를 포함했습니다. JNI/FFmpeg 실행 파일을 앱에 추가하지 않습니다. 코덱의 무료 라이선스가 음원의 저장·재배포 권리를 제공하지는 않습니다. [Fraunhofer의 핵심 MP3 특허 프로그램 종료 및 구현별 권리 설명](https://www.audioblog.iis.fraunhofer.com/mp3-software-patents-licenses)을 참고하세요(확인: 2026-11-16).
- 공개 접근 가능한 **오디오 전용 스트림**, 길이를 확인할 수 있는 **3시간 이내** 콘텐츠, 모노·스테레오만 지원합니다. 라이브·로그인·DRM·접근 제한을 우회하지 않습니다. 스트림은 기존 쿠키 없는 HTTPS 클라이언트로만 받으며 서명 URL은 저장하지 않습니다.
- 한 번에 한 곡, 원본 최대 512MiB·MP3 최대 256MiB·전체 작업 최대 30분입니다. 디스크 임시 파일과 작은 고정 버퍼로 처리하며 끝나거나 취소·실패하면 임시 파일을 정리합니다. 변환 중 추가 저장 공간과 CPU/배터리가 필요합니다. 네트워크 중단 시 자동 재개 없이 명확하게 종료하므로 다시 저장해야 합니다.
- 프로세스 강제 종료 시 자동 재개하지 않으며 선택한 위치에 빈 파일이나 불완전한 파일이 남을 수 있습니다. 앱 내부 임시 파일 두 개는 다음 저장에서 재사용하므로 계속 늘어나지 않습니다. 저장 위치 선택기는 기기 저장소를 선택하세요. 클라우드 제공자를 직접 선택하면 해당 제공자의 저장 방식이 적용됩니다.

## 1.9.0 감상 도구

- 설정 → 재생에서 음질, 오디오 전용, 연결 복구, 사전 준비 범위, 캐시 상한·비우기와 취침 페이드를 조절합니다. 정책 변경은 새 스트림 조회부터 적용하며 이미 재생 중인 형식은 유지합니다.
- 미니 플레이어에서 다음 곡으로 이동합니다. 기록·플레이리스트·즐겨찾기 제외 뒤 실행 취소로 원래 순서와 위치를 복원합니다.
- 라이브러리의 통합 검색은 제목·채널·목록 이름을 검색합니다. 버전 묶음은 제목 기준 후보를 보여주며 대표 선택과 재생만 하고 자동 삭제하지 않습니다.
- 플레이어 더보기에서 곡·채널 추천 제외를 지정합니다. 설정에서 제외를 초기화할 수 있습니다.
- 플레이어 감상 도구에서 A–B 반복, 북마크와 곡별 음량을 사용합니다. 음향 설정에는 전체 프리앰프가 있습니다. 수동 음량 보정과 자동 음량 정규화는 다릅니다.
- 가사 패널에서 로컬 LRC를 연결하고 공개 자막 언어와 시간 보정을 선택합니다. 가사 파일은 기기에만 저장합니다.
- 기기 저장에서 백업에 설정·음향·가사·북마크·기록·대기열을 선택합니다. 복원 시 설정 덮어쓰기와 대기열 교체는 별도로 동의합니다. 대기열 교체는 일시정지 상태에서 수행합니다.

[20개 개선사항 반영표](docs/IMPROVEMENTS.md) · [기기 검증 절차](docs/DEVICE_TEST.md) · [안전한 서명·릴리스 절차](docs/RELEASING.md)

## 재생 속도

플레이어 또는 설정의 배속 버튼을 누르면 0.25~4.00배를 선택할 수 있습니다. 슬라이더와 ±0.05배 버튼, 빠른 선택을 제공하며 숫자로는 0.01배 단위까지 입력할 수 있습니다. **적용**을 누르면 재생 속도가 바뀌고 앱을 다시 열어도 선택한 값이 복원됩니다.

## 음향 설정

**전체 플레이어 → 더보기(⋮) → 음향 설정** 또는 **설정 → 음향 설정**에서 재생 중 조절할 수 있습니다. 이퀄라이저는 80 Hz, 250 Hz, 1 kHz, 4 kHz, 12 kHz를 각각 −9에서 +9 dB까지 조절합니다. 원음, 저음 강조, 보컬 선명, 록, 재즈, 클래식, 팟캐스트, 넓은 스테레오, 헤드폰 공간감의 기본 프리셋 9개와 사용자 프리셋 최대 12개를 지원합니다.

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

1. **설정 → 앱 업데이트**를 열거나 이 화면으로 돌아오면 GitHub의 최신 정식 릴리스를 자동 조회합니다. 설치 버전, 정식 최신 버전, 마지막 확인 시간과 변경사항을 한 화면에서 봅니다. 새로고침 아이콘으로 다시 확인할 수도 있습니다. [GitHub 배포처](https://github.com/LibraLightLuda/AudioFlow/releases/latest)
2. 새 버전이 나오면 **다운로드 및 설치**를 누릅니다. 앱은 APK의 크기와 SHA-256, 패키지명, 버전 코드 및 설치된 앱과의 서명 일치를 확인합니다. Android에서 AudioFlow의 **이 출처 허용**을 요구하면 허용하고 돌아온 뒤 시스템 설치 화면에서 업데이트를 확인합니다.
3. 앱 내부 설치가 어려우면 **GitHub 배포처**에서 APK를 직접 받습니다. 자동 확인은 버전 조회만 하며, 다운로드와 설치는 버튼을 눌렀을 때 진행합니다. 네트워크 오류를 표시하고 무한 재시도하지 않습니다.
4. 업데이트 전에는 새 릴리스의 개선사항을, 최신 상태에서는 현재 버전의 개선사항을 표시합니다. 업데이트 후 첫 실행에서는 APK에 포함된 변경사항을 한 번 표시합니다. 네트워크 연결이 없어도 APK의 현재 버전 변경사항은 읽을 수 있습니다.

## 화면과 색 테마

**설정 → 화면과 색 테마**에서 민트, 오션 블루, 라벤더, 로즈, 앰버를 선택합니다. 선택 즉시 앱 전체에 반영되고 DataStore에 저장합니다. 다크 모드와 라이트 모드는 별도로 선택할 수 있습니다. 텍스트와 주요 컨트롤의 명암비는 두 모드에서 자동 검사합니다.

설정 첫 화면은 재생·음향·화면·저장·업데이트의 목록으로 구성했습니다. 관련 항목을 연 뒤 세부 조절을 하며, 플레이어의 보조 동작은 더보기 메뉴에 모았습니다. 설정 분류 방식은 [Spotify의 재생·음향 설정 안내](https://support.spotify.com/sa-en/article/equalizer/)와 [YouTube Music의 데이터·음질 설정 안내](https://support.google.com/youtubemusic/answer/9076559?hl=en)를 참고했습니다. 해당 앱의 코드나 자산을 복사하지 않았습니다.

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
| AndroidX WorkManager 2.11.2 (Apache-2.0) | 일괄·완청 자동 MP3 저장의 영속 작업, 순차 실행, 포그라운드 알림과 취소 | [공식 릴리스](https://developer.android.com/jetpack/androidx/releases/work), [AndroidX 소스](https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:work/) |
| Java LAME 3.98.4 (LGPL-3.0) | Android에서 디코딩한 PCM의 MP3 인코딩. 데스크톱 Java Sound 래퍼는 호출하지 않으며 배포 JAR·전체 원본 소스·라이선스를 포함합니다. | [nwaldispuehl/java-lame v3.98.4](https://github.com/nwaldispuehl/java-lame/releases/tag/v3.98.4), [출처·해시](third_party/java-lame) |
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
