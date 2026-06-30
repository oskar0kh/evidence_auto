# 범죄일람표 크롤러

디시인사이드 게시글 URL을 입력하면 게시글·댓글 정보를 파싱하고 **전체 페이지 스크린샷**을 자동 캡처한 뒤 **범죄일람표** 엑셀 파일로 다운로드하는 웹 애플리케이션입니다.

## 구조

```
evidence_auto/
├── backend/     # Spring Boot — DC Inside 크롤링 API (CORS 우회)
└── frontend/    # React + Vite — UI 및 엑셀 생성
```

브라우저에서 디시인사이드에 직접 요청하면 CORS·봇 차단 때문에 실패하므로, Spring Boot 백엔드가 페이지 HTML과 댓글 API를 대신 호출합니다.

## 사전 요구사항

- Java 17+
- Node.js 18+

> Gradle은 `backend/gradlew` Wrapper에 포함되어 있어 별도 설치가 필요 없습니다.  
> **Docker는 필요하지 않습니다.**

### 스크린샷용 Chrome (Selenium)

백엔드에서 **Selenium + headless Chrome**으로 전체 페이지를 캡처합니다.

- **Google Chrome** 또는 **Chromium** 설치 필요
- ChromeDriver는 WebDriverManager가 **설치된 Chrome 버전에 맞춰** 자동 설정
- WSL에서는 **snap Chromium**(`/snap/bin/chromium`)이 Selenium과 충돌하는 경우가 많습니다 → **Google Chrome .deb 설치 권장**

```bash
# WSL/Ubuntu — Google Chrome (권장)
wget https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb
sudo apt install -y ./google-chrome-stable_current_amd64.deb
```

`DevToolsActivePort file doesn't exist` 오류가 나면 Chrome이 시작 직후 크래시한 것입니다. 흔한 원인:

| 원인 | 해결 |
|------|------|
| Chrome 미설치 / snap만 있음 | Google Chrome .deb 설치 |
| ChromeDriver ↔ Chrome 버전 불일치 | 재시작 (자동 매칭 적용됨) |
| WSL 메모리 부족 | `--disable-dev-shm-usage` (코드에 포함) |

Chrome 경로를 직접 지정하려면 `backend/src/main/resources/application.properties`:

```properties
evidence.chrome.binary=/usr/bin/google-chrome-stable
```

### 캡처 이미지 한글이 깨질 때

캡처 PNG에서 한글이 □□□ 또는 깨진 글자로 보이면, **애플리케이션 코드 문제가 아니라 WSL/Linux에 한글 폰트가 없기 때문**인 경우가 대부분입니다.

headless Chrome은 페이지를 그릴 때 **시스템에 설치된 폰트**를 사용합니다. WSL 기본 환경에는 한글 폰트가 없는 경우가 많아, 디시인사이드처럼 한글 페이지를 캡처하면 글자가 깨져 보입니다.

#### 원인 확인

```bash
fc-list :lang=ko | head
```

아무것도 출력되지 않으면 한글 폰트가 설치되지 않은 상태입니다.

#### 해결 방법

```bash
sudo apt update
sudo apt install -y fonts-nanum fonts-noto-cjk fontconfig
fc-cache -fv
```

설치 확인:

```bash
fc-list :lang=ko | head
```

`NanumGothic`, `Noto Sans CJK KR` 등 한글 폰트 이름이 나오면 성공입니다.

설치 후 **백엔드를 재시작**하고 다시 크롤링하세요.

```bash
cd backend
./gradlew bootRun
```

#### 애플리케이션에서 하는 일

폰트 설치만으로 해결되는 문제이지만, 코드에서도 다음을 적용해 두었습니다.

- Chrome 한국어 로케일 설정 (`--lang=ko-KR`, `LANG=ko_KR.UTF-8`)
- 백엔드 시작 시 한글 폰트 미설치 **경고 로그** 출력

폰트를 설치한 뒤에도 한글이 깨지면, 백엔드 로그에 폰트 관련 경고가 남는지 확인하세요.

## 실행 방법

### 1. 백엔드 (포트 8080)

```bash
cd backend
./gradlew bootRun
```

Windows:

```bat
cd backend
gradlew.bat bootRun
```

### 2. 프론트엔드 (포트 5173)

```bash
cd frontend
npm install
npm run dev
```

브라우저에서 http://localhost:5173 을 엽니다.

## 사용 방법

1. 중앙 입력란에 디시인사이드 게시글 URL을 입력합니다. (여러 개는 줄바꿈 또는 쉼표로 구분)
2. **폴더 선택** 버튼으로 Windows·macOS 기본 폴더 선택 창에서 저장 위치를 지정합니다. (Chrome 또는 Edge 필요)
3. **크롤링 시작** 버튼을 클릭합니다. (텍스트 파싱 + 전체 페이지 스크린샷, 결과는 화면에 누적)
4. 결과 확인 후 **범죄일람표, 캡처화면 저장** 버튼을 클릭하면 선택한 폴더에 아래 파일이 저장됩니다.
   - `연번 001_post_9295.png` 형식의 캡처 PNG
     - `001` — 엑셀 **연번**(데이터 행 번호, 1부터)
     - `9295` — URL의 `&no=9295` 게시글 번호
   - `범죄일람표_YYYYMMDD_HHMM.xlsx`

### 지원 URL 예시

- `https://gall.dcinside.com/mgallery/board/view/?id=shyameoho&no=9295`
- `https://gall.dcinside.com/board/view/?id=dcbest&no=12345`

## 엑셀 컬럼

| 컬럼 | 설명 |
|------|------|
| 연번 | 1, 2, 3… |
| 게시일자 | YYYY-MM-DD HH:mm:ss |
| 닉네임 | 닉네임(IP) 형식 |
| URL | 게시글 주소 |
| 원글 내용 또는 제목 | 게시글 제목 |
| 내용 | 게시글 본문 (`[body]` 부분) |
| 댓글 작성자·내용·일시 | `내용`란 `[comments]` 아래 댓글 데이터 |
| 죄명 | 수동 입력용 (빈 값) |
| 비고 | 조회수, 댓글 수, 캡처 상대 경로 등 |
| 연번표시 캡처파일 | 저장 경로 + 캡처 PNG 파일명 (예: `C:\Users\HP\Desktop\...\연번 001_post_9295.png`) |

## 스크린샷

- 저장 위치: **폴더 선택**으로 지정한 PC 로컬 폴더 (크롤링 시 메모리에 보관, 저장 버튼 클릭 시 기록)
- 파일명: `연번 {연번}_post_{no}.png`
  - `{연번}`: 엑셀 데이터 행 번호 3자리 (`001`, `002`, …)
  - `{no}`: URL `&no=` 파라미터 값 (`9295` 등)
- Selenium headless Chrome 전체 페이지 캡처
- 캡처 시 **광고/트래킹 도메인 차단** 적용 (`evidence.screenshot.block-tracking`, 기본 `true`)
- 한글이 깨지면 README의 **「캡처 이미지 한글이 깨질 때」** 섹션 참고

## 파싱 규칙 (디시인사이드)

- **JSON-LD** (`application/ld+json`): URL, 제목, 본문, 게시일자, 조회수, 댓글 수
- **HTML** (`gall_writer`): 닉네임, IP, 식별번호
- **댓글 API** (`POST /board/comment/`): 댓글 목록 (페이지네이션 포함)

## 향후 확장

다른 사이트 크롤러는 `backend`에 사이트별 `CrawlService`를 추가하고, 프론트엔드에서 URL 도메인에 따라 분기하면 됩니다.
