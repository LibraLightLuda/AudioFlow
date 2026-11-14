# 서명과 릴리스

기존 설치 호환 인증서 SHA-256: `53d3a031547f6384a3efa67dbfbb0a0d4f640d8e5541c328f0fce76e8e0e37d9`.

새 키를 생성하거나 기존 키를 덮어쓰면 기존 설치본을 업데이트할 수 없습니다. 키 파일은 저장소·소스 ZIP·릴리스·CI 아티팩트에 넣지 않습니다. 소유자가 신뢰하는 암호화된 오프라인 저장소에 원본 키를 별도로 보관하고, 복사본으로 `keytool -list -v -keystore 경로`를 실행해 위 인증서와 일치하는지 확인합니다. 키 복사나 새 키 전환은 릴리스 스크립트가 자동 수행하지 않습니다.

PowerShell 7에서 로컬 환경 변수 `AUDIOFLOW_KEYSTORE_PASSWORD`를 설정한 후 실행합니다. 패스워드를 명령 기록이나 파일에 저장하지 않습니다.

```powershell
pwsh tools/release.ps1 -LiveChecks
# 승인된 정식 배포 시에만:
pwsh tools/release.ps1 -LiveChecks -Publish
```

스크립트는 깨끗한 커밋, 테스트·Lint·APK 빌드, ZIP 정렬, 기존 인증서 일치, 소스 ZIP, 최신 정식 릴리스 지정, 태그·업로드 해시·공개 다운로드를 검사합니다. 버전 코드와 APK의 변경사항을 먼저 갱신합니다. `-Publish`가 없으면 외부 배포하지 않습니다. Android UI 검사는 `.github/workflows/android-playback.yml`로 별도 실행합니다. 실기기 항목은 `DEVICE_TEST.md`의 결과를 별도로 남깁니다.
