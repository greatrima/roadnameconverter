안녕하세요
지번에 대한 스트레스를 줄여주기도 하며 도로명이 익숙한 분들에게는 지번을 바로 찾을 수 있기에
집배원의 업무 효율을 높히고, 스트레스를 낮추는 그야말로 1석 2조의 효과를 지닌 어플리케이션입니다.
어차피 우리들만 쓸 어플이니 플레이스토어 업로드 절차는 너무 귀찮으니까 깃허브에만 업로드합니다.

## Android 소스 코드

v1.12.1 소스와 내장 주소 사전을 공개합니다. APK 설치 파일은 [Releases](https://github.com/greatrima/roadnameconverter/releases)에서 받으실 수 있습니다.

이번 버전은 완성된 원문 주소를 사전·연속 인식 대기 없이 먼저 검색하고, 정확한 API 일치 결과만 확정합니다. 사전 교정 후보는 수동 선택으로 구분하며, 개발자 모드의 OCR 상태와 검색 상태를 분리했습니다. [v1.12.1 패치노트](FAST_SEARCH_PATCH_NOTES.md)

### 직접 빌드하기

1. 저장소를 내려받아 Android Studio에서 엽니다.
2. JDK 17과 Android SDK 35를 준비합니다. Gradle 8.9는 포함된 Wrapper가 다운로드합니다.
3. 기본 VWorld 키를 넣으려면 `local.properties.example`을 `local.properties`로 복사하고 본인의 키를 입력합니다. Android Studio가 생성한 파일이 있다면 덮어쓰지 말고 `VWORLD_API_KEY` 항목만 추가합니다.
4. Windows에서는 `gradlew.bat testDebugUnitTest assembleDebug`, macOS/Linux에서는 `./gradlew testDebugUnitTest assembleDebug`를 실행합니다.

결과: `app/build/outputs/apk/debug/app-debug.apk`

공개 소스에는 API 인증정보와 앱 서명키가 없습니다. 키 없이도 빌드와 기기 내 OCR·사전 후보 인식은 가능하지만, 새로운 주소 검증·변환에는 앱 설정에서 본인의 VWorld·네이버·카카오 API 정보를 등록하거나 빌드할 때 본인의 VWorld 키를 넣어야 합니다.

직접 빌드한 APK는 기존 배포 APK와 서명이 다를 수 있어 덮어설치가 안 될 수 있습니다. 기존 앱을 제거하면 저장한 설정이 삭제될 수 있으므로 주의해 주세요. API 키는 APK에 포함하면 추출될 수 있으며, 소스에서 제외하는 것만으로 APK 내부 키까지 보호되지는 않습니다.

- [앱 기능·사전 갱신·빌드 설명](docs/ANDROID.md)
- [v1.12.0 변경 사항과 검증 한계](PRECISE_SCAN_PATCH_NOTES.md)

주소 사전은 `app/src/main/assets/address_dictionary.tsv.gz`에 포함되어 별도로 다운로드하지 않아도 됩니다. OCR 및 사전 후보 인식과 달리, 새 지번↔도로명 변환에는 인터넷이 필요합니다.
