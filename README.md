# 범죄일람표 크롤러

디시인사이드·인스타그램 게시물 URL 또는 검색어를 입력하면 게시글·댓글 정보를 파싱하고 **스크린샷**을 자동 캡처한 뒤 **범죄일람표** 엑셀 파일로 저장하는 웹 애플리케이션입니다.

## 구조

```
evidence_auto/
├── backend/     # Spring Boot — 크롤링 API + (배포 시) UI 정적 파일
├── frontend/    # React + Vite — UI 및 엑셀 생성
├── scripts/     # 빌드 스크립트 (Windows 배포본 포함)
└── dist/        # (빌드 후 생성) Windows용 배포 폴더·zip
```

브라우저에서 디시인사이드에 직접 요청하면 CORS·봇 차단 때문에 실패하므로, Spring Boot 백엔드가 페이지 HTML과 댓글 API를 대신 호출합니다.

프로덕션/배포본에서는 Vite 빌드 결과가 Spring Boot `static`으로 포함되어 **프로세스 하나**로 UI+API를 제공합니다.

## 사전 요구사항

- Java 21+ (툴체인; Gradle Wrapper가 JDK 다운로드 가능)
- Node.js 18+
- **Google Chrome** (스크린샷·폴더 선택)

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

### A. 통합 실행 (권장 — UI+API 한 번에)

프론트를 빌드해 JAR에 넣고 Spring Boot만 실행합니다.

```bash
cd backend
./gradlew bootRun
# 또는: ./gradlew bootJar && java -jar build/libs/evidence-auto-backend-*-SNAPSHOT.jar
```

브라우저에서 http://localhost:8080 을 엽니다.  
데스크톱처럼 브라우저를 자동으로 열려면:

```bash
./gradlew bootRun --args='--spring.profiles.active=desktop'
```

스크립트:

```bash
chmod +x scripts/build-jar.sh
./scripts/build-jar.sh
```

백엔드만 빠르게 돌리려면 프론트 빌드를 건너뛸 수 있습니다: `./gradlew bootRun -PskipFrontend`

### B. 개발 모드 (핫 리로드)

#### 1. 백엔드 (포트 8080)

```bash
cd backend
./gradlew bootRun -PskipFrontend
```

Windows:

```bat
cd backend
gradlew.bat bootRun -PskipFrontend
```

#### 2. 프론트엔드 (포트 5173)

```bash
cd frontend
npm install
npm run dev
```

브라우저에서 http://localhost:5173 을 엽니다. (`/api`는 Vite가 8080으로 프록시)

### C. Windows 스탠드얼론 배포본 (WSL에서 빌드 → Windows에서 실행)

개발용 소스(`frontend/`, `backend/src/` 등)를 사용자에게 줄 필요 없습니다.  
**WSL에서 한 번 빌드**하면 Windows용 폴더/zip이 나오고, 사용자는 그것만 받아 실행합니다.

구성 요약:

| 역할 | 내용 |
|------|------|
| 빌드하는 사람 | WSL + Node + JDK로 `scripts/build-windows-dist.sh` 실행 |
| 결과물 | `dist/EvidenceAuto/` 폴더 또는 `dist/EvidenceAuto-windows.zip` |
| 사용하는 사람 | 폴더/zip만 받음. JDK·Node·소스 불필요. **Google Chrome은 필요** |

---

#### C-1. 빌드 환경 (WSL)

프로젝트 루트에서:

```bash
cd ~/daeryun/evidence_auto   # 본인 클론 경로로 변경
```

필요 도구:

- Node.js 18+
- JDK 21 (Gradle 실행용; 시스템에 JDK 25만 있으면 스크립트가 JDK 21 경로를 우선 사용)
- `curl` (Windows JRE 다운로드)
- (선택) `mingw-w64` — `EvidenceAuto.exe`까지 만들 때

```bash
# exe 런처까지 만들 경우 (최초 1회, 선택)
sudo apt install -y mingw-w64
```

---

#### C-2. 빌드 명령

```bash
cd ~/daeryun/evidence_auto
chmod +x scripts/build-windows-dist.sh
./scripts/build-windows-dist.sh
```

또는 Gradle 태스크:

```bash
cd ~/daeryun/evidence_auto/backend
./gradlew windowsDist
```

스크립트가 하는 일:

1. 프론트엔드 `npm` 빌드  
2. Spring Boot fat JAR 생성 (UI static 포함)  
3. Windows용 JRE 21 다운로드(캐시: `.cache/`)  
4. `dist/EvidenceAuto/` 조립 + zip 생성  
5. (mingw 있으면) `EvidenceAuto.exe` 생성  

첫 빌드는 JRE 다운로드(~50MB) 때문에 조금 걸릴 수 있습니다. 이후는 캐시를 재사용합니다.

---

#### C-3. 빌드 결과물 위치

성공 시 프로젝트 루트 아래:

```
evidence_auto/
└── dist/
    ├── EvidenceAuto/                 ← 배포 폴더 (이것만 복사해도 됨)
    │   ├── EvidenceAuto.exe          ← (mingw 있을 때) 런처
    │   ├── EvidenceAuto.bat          ← bat 런처
    │   ├── HOW-TO-RUN.txt            ← Windows 실행 안내
    │   ├── app/
    │   │   └── evidence-auto.jar     ← 앱 본체
    │   └── runtime/                  ← 번들 Windows JRE
    │       └── bin/java.exe
    └── EvidenceAuto-windows.zip      ← 압축본 (사용자에게 주기 좋음)
```

확인 예:

```bash
ls -la dist/
ls -la dist/EvidenceAuto/
ls -la dist/EvidenceAuto/app/
```

---

#### C-4. Windows로 가져오기

**방법 A — zip 복사 (권장)**

1. WSL에서 zip 경로 확인: `dist/EvidenceAuto-windows.zip`  
2. Windows 탐색기에서  
   `\\wsl$\Ubuntu\home\<유저명>\daeryun\evidence_auto\dist\`  
   (배포판 이름·경로에 맞게) 로 들어가 zip을 바탕화면 등으로 복사  
3. 또는 USB / 공유폴더 / 메신저로 zip 전달  
4. Windows에서 zip 압축 해제 → `EvidenceAuto` 폴더 생성  

**방법 B — 폴더 통째 복사**

`dist/EvidenceAuto/` 폴더 전체를 Windows로 복사합니다.  
(`app`, `runtime`이 빠지면 실행되지 않습니다.)

사용자에게 **주지 않아도 되는 것**: `frontend/`, `backend/src/`, `node_modules/`, 전체 git 저장소.

---

#### C-5. Windows에서 실행 (권장: cmd + java.exe)

Windows **Smart App Control / SmartScreen**이 서명 없는 `EvidenceAuto.exe` / `.bat` 를 막는 경우가 많습니다.  
그럴 때는 **시작 메뉴에서 연 명령 프롬프트(cmd)** 로, 서명이 있는 번들 `java.exe`를 직접 실행하세요.

1. 대상 PC에 **Google Chrome** 설치 확인  
2. `Win` 키 → `cmd` → **명령 프롬프트** 실행  
3. 아래처럼 입력 (경로는 본인 PC에 맞게 수정):

```bat
cd /d "C:\Users\방금받은경로\EvidenceAuto"
runtime\bin\java.exe -Dspring.profiles.active=desktop -Devidence.open-browser=true -jar app\evidence-auto.jar
```

실제 예:

```bat
cd /d "C:\Users\just\Desktop\디지털포렌식센터\크롤링\EvidenceAuto"
runtime\bin\java.exe -Dspring.profiles.active=desktop -Devidence.open-browser=true -jar app\evidence-auto.jar
```

4. 로그에 다음이 보이면 기동 성공입니다.

```text
Evidence Auto UI: http://127.0.0.1:8080/
Started EvidenceAutoApplication
```

5. 브라우저(Chrome/Edge)에서 **http://127.0.0.1:8080/** 을 엽니다.  
   (자동으로 안 열리면 주소창에 직접 입력)
6. **cmd 창은 사용 중 닫지 마세요.** 닫으면 서버가 종료됩니다.  
   종료할 때는 창에서 `Ctrl+C` 또는 창 닫기.

Chrome을 찾못 못하면(설치 위치가 특이한 경우) 경로를 지정합니다:

```bat
runtime\bin\java.exe -Dspring.profiles.active=desktop -Devidence.open-browser=true -Devidence.chrome.binary="C:\Program Files\Google\Chrome\Application\chrome.exe" -jar app\evidence-auto.jar
```

폴더 안 `HOW-TO-RUN.txt` 에도 같은 안내가 있습니다.

---

#### C-6. exe / bat 더블클릭 (막히지 않을 때만)

정책이 느슨한 PC에서는:

- `EvidenceAuto.exe` 또는 `EvidenceAuto.bat` 더블클릭  

으로도 실행될 수 있습니다.  
**차단되면 C-5(cmd + java.exe)를 사용**하세요.  
`차단 해제`만으로는 Smart App Control 강제 모드를 우회하지 못하는 경우가 많습니다.

---

#### C-7. 사용자 PC 요구사항 / 데이터 위치

| 항목 | 내용 |
|------|------|
| JDK / Node / 소스 | **불필요** (runtime에 JRE 포함) |
| Google Chrome | **필요** (스크린샷·폴더 선택) |
| 앱 데이터 | `%APPDATA%\EvidenceAuto\` (쿠키·crash.log 등) |
| 실행 로그 | 배포 폴더의 `logs\evidence-auto.log` |

---

#### C-8. (선택) Windows 호스트에서 jpackage로 빌드

`jpackage`는 **빌드하는 OS용**만 만들 수 있어, 순수 Windows jpackage exe는 **Windows PC**에서:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-windows.ps1
```

일반 배포는 **C-2 WSL 빌드**로 충분합니다.

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

## 인스타그램

- UI 상단 탭에서 **인스타그램** 선택
- **URL 직접입력**: `instagram.com/p/`, `/reel/`, `/tv/` URL 크롤링
- **검색어**: Instagram 탐색(Explore) 검색으로 URL 수집 후 크롤링 (로그인 세션 권장)
- 엑셀 컬럼: `작성 형태 (게시글/댓글)`, 닉네임=Instagram ID, 댓글란=ID 기반 (IP 없음)
- 검색어 매칭: 캡션·댓글 텍스트 + **이미지 OCR** (Tesseract `kor+eng`)
- 캡처: 게시글 전체 레이아웃 / 매칭 댓글 노란 하이라이트

### 인스타그램 설정 (`application.properties`)

```properties
evidence.instagram.session-cookies=sessionid=...;csrftoken=...
evidence.instagram.doc-id.post=8845758582119845
evidence.instagram.doc-id.comments=26248690958161038
evidence.instagram.doc-id.child-comments=26914912424764761
```

OCR 사용 시 시스템에 Tesseract와 한글 데이터가 필요합니다:

```bash
sudo apt install -y tesseract-ocr tesseract-ocr-kor
```

## 향후 확장

다른 사이트 크롤러는 `backend`에 사이트별 `CrawlService`를 추가하고, 프론트엔드에서 URL 도메인에 따라 분기하면 됩니다.
