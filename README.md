# 🐴 적토마 실시간 레이스 (Jeoktoma Race)

**실시간 경주 베팅 게임 & 고가용성(HA) 아키텍처 실습 프로젝트**

이 프로젝트는 간단한 웹 게임을 통해 **Spring Boot 기반의 백엔드 개발**, **동시성 처리**, 그리고 **AWS EC2 환경에서의 3-Tier 아키텍처 구축**을 실습하기 위해 만들어졌습니다. 특히 다중 서버 환경(Active-Active)에서의 **스케줄러 중복 실행 문제**를 DB 기반의 리더 선출(Leader Election) 알고리즘으로 해결한 것이 핵심입니다.

---

## 🛠️ 기술 스택 (Tech Stack)

### Backend
- **Language:** Java 17
- **Framework:** Spring Boot 2.7.0
- **Database Access:** Spring Data JPA, JDBC Template
- **Build Tool:** Maven

### Frontend
- **Language:** HTML5, CSS3, JavaScript (Vanilla)
- **Style:** Retro Pixel Art & Neon UI

### Infrastructure & Database
- **Cloud:** AWS EC2 (Public/Private Subnet)
- **Web Server:** Nginx (Reverse Proxy & Static Content Serving)
- **Database:** MySQL 8.0 (AWS RDS)

---

## 🏗️ 시스템 아키텍처 (Architecture)

### 3-Tier Architecture
보안과 확장성을 고려하여 **Web - WAS - DB** 3계층 구조로 설계되었습니다.

1.  **Web Tier (Public Subnet):** Nginx가 정적 리소스(HTML, JS, Image)를 처리하고, 동적 API 요청만 WAS로 전달합니다.
2.  **App Tier (Private Subnet):** Spring Boot WAS가 비즈니스 로직(게임 진행, 베팅 처리)을 담당합니다. 외부에서 직접 접근할 수 없습니다.
3.  **Data Tier (DB Subnet):** RDS(MySQL)에 사용자 정보와 게임 상태를 저장합니다.

### 고가용성(HA) 및 분산 처리
WAS를 2대 이상(Active-Active) 운영할 때 발생하는 **스케줄러 중복 실행(Double Scheduling)** 문제를 해결하기 위해 **DB 기반의 리더 선출(Leader Election)** 방식을 도입했습니다.

*   모든 서버는 주기적으로 DB에 Heartbeat를 보냅니다.
*   **Leader 서버**로 선출된 단 하나의 인스턴스만 게임 타이머와 경주 로직을 수행합니다.
*   Leader 서버 장애 시, 5초 이내에 다른 서버가 Leader 권한을 승계(Failover)하여 서비스 중단을 방지합니다.

---

## 🎮 주요 기능 (Features)

*   **실시간 경주:** 4마리의 말(적토마, 청토마, 백토마, 흑토마)이 실시간으로 경주를 펼칩니다.
*   **베팅 시스템:** 사용자는 보유 포인트를 사용하여 우승마를 예측하고 베팅할 수 있습니다.
*   **하드코어 룰:** 포인트가 0이 되어 파산하면 **계정과 모든 기록이 즉시 삭제**됩니다.
*   **이어하기 지원:** 닉네임 기반으로 기존 정보를 로드하며, 없는 닉네임일 경우 자동으로 회원가입됩니다.
*   **실시간 랭킹:** 수익이 가장 높은 상위 10명의 랭킹을 실시간으로 제공합니다.

---

## 🚀 설치 및 실행 방법 (How to Run)

### 1. 사전 요구 사항
*   Java 17 이상
*   MySQL 데이터베이스 (로컬 또는 원격)

### 2. 데이터베이스 설정
`src/main/resources/application.properties` 파일에서 DB 연결 정보를 수정합니다.
```properties
spring.datasource.url=jdbc:mysql://<DB_HOST>:3306/jeoktoma
spring.datasource.username=<USERNAME>
spring.datasource.password=<PASSWORD>
```
최초 실행 시 `db` 파일의 SQL 쿼리를 실행하거나, 서버가 자동으로 스키마를 업데이트합니다.

### 3. 빌드 및 실행
```bash
# 빌드
mvn clean package -DskipTests

# 실행
java -jar target/race-1.0.0.war
```
브라우저에서 `http://localhost:8080` 접속.

---

## ⚙️ 설정 및 버전 관리 (Configuration)

### 1. 앱 버전 관리
`src/main/resources/application.properties` 파일에서 애플리케이션 버전을 관리합니다. 배포 전 이 값을 수정하면 화면 하단에 반영됩니다.
```properties
app.version=v1.0
```

### 2. Nginx 버전 표시 설정
웹 화면 하단에 Nginx 버전을 함께 표시하려면, Nginx 설정(`nginx.conf` 등)의 `/api/` 프록시 설정에 다음 헤더를 추가해야 합니다.
```nginx
proxy_set_header X-Nginx-Version $nginx_version;
```
(상세 설정 예시는 프로젝트 루트의 `nginx_setting` 파일을 참고하세요.)

---

## 📂 프로젝트 구조
```
├── src/main/java/com/jeoktoma
│  ...
├── src/main/resources
│   ├── static/              # Frontend (HTML, CSS, JS)
│   └── application.properties
├── nginx_setting            # Nginx 설정 예시
├── db                       # 초기 SQL 스키마
└── pom.xml                  # Maven 설정
```
