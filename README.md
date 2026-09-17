안녕하세요. 어딘가에서 근무 중인 집배원입니다.

## 개발 동기
GPT6-Astro로 전기를 낭비하고 싶었는데 마침 지번으로 스트레스 받는 집배원의 게시글을 보았습니다.
이야기 해보니 신규 집배원이나 통구가 잦은 구역에서 자주 관찰되는 현상이었는데요.
마침 최적의 아이디어가 떠올랐기에 바로 실천으로 옮겼습니다.

많은 분들의 피드백 덕분에 업무 효율을 높히고, 지번 스트레스를 낮추며, 반대로 도로명을 지번으로 바꾸기도 원할한 어플을 완성했습니다.
어차피 우리들만 쓸 어플이니 플레이스토어 업로드 절차는 귀찮은 관계로 깃허브에만 업로드 합니다.

iOS의 경우 개발자 등록부터 돈이 들어서 가난한 9급은 그런 어려운 일을 할 수 없습니다.
저도 아이폰 쓰지만 포기합니다.

대신 소스코드 전체를 공개하니 필요하신 분은 직접 GPT를 돌려서 포팅 하시고 테스트플라이트 설치하셔서 활용하셔도 좋겠습니다.

## Android 소스 코드

v2.0.1 소스와 내장 주소 사전을 공개합니다. 배포된 APK 설치 파일은 [Releases](https://github.com/greatrima/roadnameconverter/releases)에서 확인하실 수 있습니다.

이번 버전은 버튼별 표시 설정과 주소 복사, 수동 오프라인 모드와 볼륨키 확대·축소를 포함합니다. 배포명과 APK 내부 버전이 달라 업데이트 안내가 반복되던 문제를 수정했습니다. [v2.0.1 패치노트](RELEASE_NOTES_v2.0.1.md)

이전 버전의 여러 줄 주소 복원·미완성 번지 처리·정확한 API 일치 검증은 유지합니다. [v2.0.0 패치노트](RELEASE_NOTES_v2.0.0.md)

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
