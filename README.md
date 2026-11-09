# HWP → PDF

HWP·HWPX 파일을 안드로이드 기기 안에서 PDF로 바꾸는 앱과 홈 화면 위젯입니다. 한컴 앱이 필요 없고, 파일을 서버로 보내지 않습니다.

**설치:** [hwptopdf.apk 받기](https://github.com/fishilyfishyfish/hwptopdf/releases/download/latest/hwptopdf.apk)

- 위젯을 누르면 파일을 고르는 화면이 열립니다.
- 다른 앱의 공유 메뉴에서 이 앱을 골라도 변환됩니다.
- PDF는 `다운로드/HWP2PDF`에 저장됩니다.
- 각 쪽은 이미지로 저장되고, 그 위에 보이지 않는 글자 층이 있어서 검색과 복사가 됩니다.
- 옛한글과 방점을 지원합니다. 방점은 음절 뒤에 저장된 경우 음절 왼쪽에 표시합니다.
- 비밀번호가 걸린 문서는 변환하지 못합니다.

## 만든 재료

| 이름 | 용도 | 라이선스 |
| --- | --- | --- |
| [rhwp](https://github.com/edwardkim/rhwp) | HWP/HWPX 해석과 조판 | MIT |
| Noto Serif KR, Noto Sans KR | 본문 글꼴 대체 | SIL OFL 1.1 |
| Pretendard | 본문 글꼴 대체 | SIL OFL 1.1 |
| Latin Modern Math | 수식 글꼴 | GUST Font License |
| RHWP Old Hangul (Source Han Serif 옛한글) | 옛한글 | SIL OFL 1.1 |
| RHWP Public Batang (Google Fonts Batang) | 명조 한자 | SIL OFL 1.1 |

엔진과 글꼴은 빌드할 때 `tools/fetch-assets.sh`가 받아 옵니다. 라이선스 전문은 앱의 `assets/licenses`에 들어갑니다.
