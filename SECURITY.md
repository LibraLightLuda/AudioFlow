# AudioFlow 1.2.1 요청·정보 보호

## 1.4.0 GitHub 업데이트

사용자가 **최신 버전 확인**을 누르면 GitHub 공개 REST API에서 AudioFlow의 최신 정식 릴리스를 읽습니다. 선택한 APK는 저장소의 정확한 릴리스 경로에서 요청하고, GitHub 릴리스 파일 CDN으로의 HTTPS 이동만 허용합니다. 업데이트용 HTTP 클라이언트는 쿠키나 인증을 사용하지 않습니다.

APK는 크기·SHA-256, 패키지명·버전명, 더 높은 버전 코드와 설치된 앱의 서명 인증서를 확인합니다. 확인한 파일만 제한된 FileProvider 경로로 Android 설치 화면에 전달합니다. 설치는 Android의 출처 허용 및 사용자 확인을 거칩니다.

2026-11-09 기준 구현입니다. 이 기능은 계정 정보와 민감한 요청 정보의 불필요한 전달·저장을 줄입니다. YouTube에 보이지 않는 요청이나 계정 제재 방지를 보장하지 않습니다.

## 적용 범위

- 검색·메타데이터·공개 자막, 오디오 스트림, 화면 및 미디어 알림의 썸네일이 동일한 `PrivateNetwork` 정책을 사용합니다.
- 쿠키 저장소는 `CookieJar.NO_COOKIES`입니다. 입력 `Cookie`, `Authorization`, `Proxy-Authorization`, 계정 선택 헤더, 임의 기기 식별 헤더, `Referer`·`Origin` 등은 허용 목록에서 제외합니다. 응답 `Set-Cookie`도 호출자에게 전달하지 않습니다. 시스템 `CookieHandler`, 브라우저 및 WebView 쿠키 저장소를 사용하지 않습니다.
- 공개 조회에 필요한 콘텐츠 유형·언어·범위·클라이언트 버전 헤더만 전달합니다. 추출기가 사용하는 `X-Goog-Visitor-Id`는 로그인 정보가 아닌 익명 방문자 정보이며 조회 요청에서만 허용합니다. 같은 익명 세션의 요청을 연관 짓는 데 사용될 수 있습니다. 이 앱은 이를 영구 저장하지 않습니다.
- HTTPS와 기본 443 포트만 허용합니다. 계정명이 들어간 URL, 임의 외부 서버, 로그인 서버, IP 리터럴 및 로컬 파일 URL은 HTTP 클라이언트에서 차단합니다. Android의 시스템 CA와 기본 호스트명 검증을 사용합니다. 사용자 추가 CA를 신뢰하도록 구성하지 않습니다.
- 목적별 호스트를 구분합니다. 조회는 YouTube 및 필요한 지정 엔드포인트, 스트림은 `*.googlevideo.com`, 이미지는 `ytimg.com`, `ggpht.com` 계열 및 `yt3.googleusercontent.com`입니다. 임의 `google.com`·`googleapis.com` 전체를 허용하지 않습니다.
- 자동 리디렉션을 끄고 목적지를 DNS 조회 및 연결 전에 검사합니다. 최대 5회 이동을 허용하고 다른 호스트로 POST 본문을 전달하지 않습니다. 허용된 CDN 간 GET 이동은 바이트 범위를 유지하며 클라이언트 메타데이터를 제거합니다.
- 공유 링크는 동영상·채널·플레이리스트의 정규 주소로 다시 구성합니다. `si`, 공유 추적 값, 임의 토큰이 포함된 원본 URL을 그대로 요청하지 않습니다.
- 일반 HTTP 디스크 캐시와 새 썸네일 디스크 캐시를 끕니다. 스트림의 기존 24 MiB 순환 캐시는 유지하며 서명 URL이 캐시 리디렉션 메타데이터에 남지 않게 합니다. 라이브러리·대기열에는 동영상 ID와 공개 메타데이터를 저장하고 서명 스트림 주소를 저장하지 않습니다.
- Media3 진단 로그·스택 트레이스를 끄고 앱의 저장 오류 로그에서 예외 원문을 제외합니다. 화면에는 원본 HTTP URL이나 인증 값을 포함하지 않는 오류 문구를 표시합니다.
- 메타데이터 응답은 16 MiB, 자막은 512 KiB를 넘으면 중단합니다. 연결·읽기 제한 시간과 기존의 제한된 재시도 정책을 유지합니다. 차단 요청을 무한 재시도하지 않습니다.
- 기존 Android 백업 제외와 MediaSession의 호출자 검증을 유지합니다. 임의 외부 앱은 재생 대기열 추가 및 사용자 정의 명령을 실행할 수 없습니다. 시스템·신뢰된 미디어 컨트롤러는 알림 및 블루투스 제어에 필요한 권한을 유지합니다.

## 보장하지 않는 사항

- YouTube는 직접 통신의 공인 IP, 요청 시간·빈도, 검색어·영상 ID, TLS·HTTP 특성과 익명 방문자 정보를 볼 수 있습니다. 서버가 요청을 서로 연관 짓거나 차단하는 것을 막지 않습니다.
- API 주소나 호출 구조는 APK에서 분석할 수 있습니다. APK 안에 반드시 비밀로 유지되어야 하는 API 키·계정 비밀번호를 넣지 않습니다. 현재 기능은 사용자 소유 Data API 키나 OAuth 자격 증명을 사용하지 않습니다.
- **YouTube에서 채널 열기**는 사용자가 선택한 외부 앱·브라우저로 이동합니다. 그 앱에 로그인되어 있으면 그 이후 활동에는 해당 계정이 적용될 수 있습니다. AudioFlow가 외부 앱의 세션을 제어하지 않습니다.
- Android 시스템, 루팅된 기기, 사용자가 접근을 허용한 미디어·알림 앱, 이전 버전에서 이미 남긴 로그·캐시까지 지우거나 숨기지 않습니다. 사용자의 기존 로컬 기록과 플레이리스트는 유지합니다.
- 앱 전체 메모리 사용량이 8 MiB라는 뜻은 아닙니다. 8 MiB는 기존 재생 버퍼 목표이고, 이미지·추출기·런타임은 별도 메모리를 사용합니다.
- 보안 강화는 YouTube 약관상의 허가나 탐지 회피를 의미하지 않습니다. 허용된 호스트나 익명 접근 방식이 바뀌면 업데이트가 필요할 수 있습니다.

## 검증과 근거

`PrivateNetworkTest`는 헤더 필터, 목적별 URL 검증, 연결 전에 막히는 리디렉션, 리디렉션 상한, POST 본문 보호, CDN 범위 요청과 응답 크기 제한을 검사합니다. `CatalogTest`의 선택적 실서비스 테스트는 실제 공개 조회·미디어 바이트 및 합성 쿠키가 있는 JVM 환경에서 쿠키 분리를 검사합니다. 테스트의 합성 응답은 보안 경계 검사에만 사용하며 앱의 검색·재생 데이터에 사용하지 않습니다.

APK·실서비스·기기 검증 결과는 [VERIFICATION.md](VERIFICATION.md)에 기록합니다.

참고한 공식 문서, 확인일 2026-11-09:

- Android Developers, [Network stacks](https://developer.android.com/media/media3/exoplayer/network-stacks): Media3에 지정 HTTP 클라이언트 적용.
- Android Developers, [Network security configuration](https://developer.android.com/privacy-and-security/security-config): HTTPS 및 신뢰 CA 설정.
- Android Developers, [DataSourceBitmapLoader](https://developer.android.com/reference/kotlin/androidx/media3/datasource/DataSourceBitmapLoader): 알림 이미지 로더의 데이터 소스 지정.
