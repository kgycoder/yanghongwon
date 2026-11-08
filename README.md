# Arena Agent

LMArena(`https://arena.ai/text/direct?model_a=max`) 웹을 AI 백엔드로 쓰고, Android 접근성 서비스로 기기를 직접 조작하는 에이전트 앱.

## 빌드 (GitHub Actions)
1. 이 폴더를 GitHub 저장소에 push (main/master)
2. Actions 탭 → **Build APK** → Artifacts 의 `arena-agent-debug-apk` 다운로드
3. APK 설치

## 첫 실행
1. 앱 실행 → 로딩 UI → Google 로그인 창 → 로그인 (쿠키는 저장되어 재실행 시 유지)
2. 상단 좌측 버튼 또는 "접근성 서비스 켜기" → **Arena Agent** 활성화
   - Android 13+ 사이드로드 앱: 앱 정보 → ⋮ → **제한된 설정 허용** 후 활성화
3. 명령 입력 (예: "설정 앱에서 Wi-Fi 상태 알려줘")

## 구조
- `ArenaController` : WebView 로 LMArena 로그인/전송/스트리밍/중단/새 세션 (DOM 주입)
- `WorkParser` : 응답의 ```work JSON 블록 해석
- `AgentViewModel` : 목표 달성까지 반복하는 에이전트 루프 (최대 40 step)
- `AgentAccessibilityService` : 화면 덤프 + tap/swipe/scroll/type/key/launch_app/open_url/wait
- `ui/Glass.kt` : Kyant0/AndroidLiquidGlass (backdrop 1.0.6) 기반 캡슐 글래스 UI

## 속도 최적화 (v1.1)
단순 작업이 느렸던 원인(매 단계 LLM 왕복 + 고정 delay + UI 클릭 위주)을 구조적으로 줄였습니다.
- `FastPath` : "볼륨 70%", "밝기 올려", "손전등 켜줘", "무음 모드", "다음 곡" 같은 단순 명령은 **LLM 호출 없이 즉시** 실행
- `DeviceApi` : 시스템/앱 API 실행기 (음량·밝기·손전등·벨소리 모드·방해금지·미디어·알람/타이머·설정 페이지 직행·딥링크 인텐트·다이얼/문자작성/검색/지도). 접근성 서비스가 꺼져 있어도 동작
- 프롬프트: API 우선 → 딥링크 → UI 최후 수단, 한 턴에 명령 일괄 전송(최대 12), API 전용이면 같은 턴에 `done`
- 현재 시스템 상태(음량/밝기/Wi-Fi/BT/DND/배터리 등)를 처음부터 제공 → "읽기 위해 설정 앱 열기" 제거
- API 전용 턴 뒤에는 화면 덤프 생략 (`"observe":true` 로 요청 가능)
- 고정 `delay` → 접근성 이벤트 기반 `settle()` (화면이 안정되면 즉시 진행)
- 응답 스트리밍에서 `work` 블록이 완성되면 생성 종료를 기다리지 않고 즉시 진행, 폴링 250→150ms
- `done` 인데 실행 중 오류가 나면 끝내지 않고 결과를 돌려 재시도
