# BlockBlast Helper (Android Kotlin)

블록블라스트(Block Blast!) 게임 화면 위에 플로팅 버튼으로 동작하며, **자동 배치 추천**과 **자동 배치 실행(접근성 제스처)**을 지원하는 Android Studio 프로젝트입니다.

## 프로젝트 구조
- `solver/BlockBlastSolver.kt`: 64비트 비트마스크 기반 8×8 보드 3-블록 순열($3! = 6$) 탐색 솔버 (평상시 콤보/고득점 모드 ↔ 위기 시 3×3 공간 확보 및 고립 구멍 제거 생존 우선 모드 자동 전환)
- `vision/ScreenAnalyzer.kt`: `MediaProjection` 화면 캡처에서 8×8 보드, 하단 3개 블록 형태, 상단 점수(ML Kit OCR) 추출
- `overlay/FloatingOverlayService.kt`: 드래그 가능한 플로팅 버튼 및 4대 기능 드롭다운 메뉴 (`추천기`, `자동 실행`, `목표 점수`, `실행 시간`)
- `overlay/HintOverlayView.kt`: 보드 빈 칸의 연한 노란색(`#66FFF176`) + 순서 숫자 오버레이 및 하단 블록 위 순서 배지 표시
- `accessibility/AutoDragAccessibilityService.kt`: `dispatchGesture` 기반 자동 드래그 실행
- `controller/AutomationController.kt`: 목표 점수 도달 및 실행 시간 타이머 만료 시 자동 정지 제어

## Android Studio 빌드 및 실기기 적용 시 참고
1. Android Studio(AGP 8.5+, JDK 17)에서 프로젝트 루트(`BlockBlastHelper`)를 엽니다.
2. 기기 해상도·노치 높이에 따라 `BoardCalibration.defaultForScreen`의 보드/슬롯 비율(`boardTop`, `boardSize`, `slotTop`)이 다를 수 있으므로, 테스트 기기 스크린샷 기준으로 좌표를 미세 조정하세요.
3. 실제 기기 실행 후 **오버레이 권한 → 접근성 서비스 → 화면 캡처 권한** 순서로 허용한 뒤 게임을 실행하면 됩니다.
