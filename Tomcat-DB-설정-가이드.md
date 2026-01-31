# Tomcat & DB 설정 가이드

## 📍 설정 파일 위치

```
source-code/src/main/resources/application.yml
```

---

## 🔧 Tomcat 설정 (성능 튜닝)

### 위치: `application.yml` 파일의 `server.tomcat` 섹션

```yaml
server:
  port: 8080
  tomcat:
    threads:
      max: 500            # 최대 스레드 수
      min-spare: 50       # 최소 유지 스레드 수
    accept-count: 1000    # 대기열 크기
```

### 설정 설명

#### 1. `threads.max: 500`
- **의미**: 동시에 처리할 수 있는 최대 요청 수
- **기본값**: 200
- **권장값**: 
  - 소규모 (100명 이하): 200
  - 중규모 (500명): 500
  - 대규모 (1000명 이상): 1000

**계산 공식:**
```
max threads = 예상 동시 접속자 × 1.2 (여유분)
예: 400명 × 1.2 = 480 → 500으로 설정
```

#### 2. `threads.min-spare: 50`
- **의미**: 항상 대기 상태로 유지할 최소 스레드 수
- **기본값**: 10
- **효과**: 갑작스런 트래픽 증가 시 빠른 응답

#### 3. `accept-count: 1000`
- **의미**: 모든 스레드가 사용 중일 때 대기열에 쌓을 수 있는 요청 수
- **기본값**: 100
- **효과**: 순간적인 트래픽 폭증 시 요청 거부 방지

### 동작 원리

```
요청 처리 흐름:
1. 요청 도착
2. 사용 가능한 스레드 있음? 
   → YES: 즉시 처리
   → NO: accept-count 대기열에 추가
3. 대기열도 꽉 참?
   → 503 Service Unavailable 에러 반환
```

---

## 🗄️ DB 설정 (HikariCP 커넥션 풀)

### 위치: `application.yml` 파일의 `spring.datasource.hikari` 섹션

```yaml
spring:
  datasource:
    url: jdbc:mysql://${host}:${port}/${dbname}?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
    username: ${username}
    password: ${password}
    driver-class-name: com.mysql.cj.jdbc.Driver
    
    hikari:
      maximum-pool-size: 100      # 최대 커넥션 수
      connection-timeout: 30000   # 연결 대기 시간 (ms)
      minimum-idle: 20            # 최소 유지 커넥션 수
```

### 설정 설명

#### 1. `maximum-pool-size: 100`
- **의미**: DB에 동시에 연결할 수 있는 최대 커넥션 수
- **기본값**: 10
- **권장값**:
  - 소규모: 20~50
  - 중규모: 50~100
  - 대규모: 100~200

**계산 공식:**
```
maximum-pool-size = (Tomcat max threads × 0.2) ~ (Tomcat max threads × 0.5)
예: 500 threads × 0.2 = 100
```

**주의사항:**
- RDS의 `max_connections` 설정보다 작아야 함
- RDS db.t3.micro 기본값: 약 85개
- RDS db.t3.small 기본값: 약 150개

#### 2. `connection-timeout: 30000`
- **의미**: 커넥션을 얻기 위해 대기하는 최대 시간 (밀리초)
- **기본값**: 30000 (30초)
- **효과**: 30초 내에 커넥션 못 얻으면 에러 발생

#### 3. `minimum-idle: 20`
- **의미**: 항상 유지할 최소 커넥션 수
- **기본값**: maximum-pool-size와 동일
- **효과**: 갑작스런 요청 증가 시 빠른 응답

### HikariCP 동작 원리

```
커넥션 풀 동작:
1. 애플리케이션 시작 시 minimum-idle 개수만큼 커넥션 생성
2. 요청 증가 시 maximum-pool-size까지 자동 증가
3. 사용 후 커넥션 반환 (재사용)
4. 유휴 시간 초과 시 minimum-idle까지 감소
```

---

## 🔐 AWS Secrets Manager 연동

### 위치: `application.yml` 파일의 `spring.config.import` 섹션

```yaml
spring:
  config:
    import: aws-secretsmanager:jeoktoma-db
```

### 동작 원리

1. **애플리케이션 시작 시**:
   - EC2 인스턴스의 IAM Role 사용
   - Secrets Manager에서 `jeoktoma-db` 시크릿 조회
   - JSON 형식의 값을 환경 변수로 주입

2. **Secrets Manager에 저장된 값 예시**:
```json
{
  "host": "jeoktoma-db.xxxxx.ap-northeast-2.rds.amazonaws.com",
  "port": "3306",
  "dbname": "jeoktoma",
  "username": "admin",
  "password": "your-secure-password"
}
```

3. **application.yml에서 사용**:
```yaml
datasource:
  url: jdbc:mysql://${host}:${port}/${dbname}
  username: ${username}
  password: ${password}
```

### 장점
- ✅ 코드에 비밀번호 하드코딩 안 함
- ✅ AMI에 민감 정보 포함 안 됨
- ✅ 비밀번호 변경 시 코드 수정 불필요
- ✅ IAM 권한으로 접근 제어

---

## 📊 성능 튜닝 시나리오

### 시나리오 1: 동시 접속자 100명 (소규모)

```yaml
server:
  tomcat:
    threads:
      max: 150
      min-spare: 20
    accept-count: 300

spring:
  datasource:
    hikari:
      maximum-pool-size: 30
      minimum-idle: 10
```

**예상 성능:**
- RPS: 100~200
- CPU: 20~30%
- 메모리: 512MB

---

### 시나리오 2: 동시 접속자 500명 (중규모) ⭐ 현재 설정

```yaml
server:
  tomcat:
    threads:
      max: 500
      min-spare: 50
    accept-count: 1000

spring:
  datasource:
    hikari:
      maximum-pool-size: 100
      minimum-idle: 20
```

**예상 성능:**
- RPS: 500~800
- CPU: 50~70%
- 메모리: 1GB

---

### 시나리오 3: 동시 접속자 1000명 (대규모)

```yaml
server:
  tomcat:
    threads:
      max: 1000
      min-spare: 100
    accept-count: 2000

spring:
  datasource:
    hikari:
      maximum-pool-size: 200
      minimum-idle: 50
```

**주의사항:**
- RDS 인스턴스 타입 업그레이드 필요 (db.t3.small 이상)
- EC2 인스턴스 타입 업그레이드 필요 (t3.medium 이상)
- RDS `max_connections` 설정 확인

---

## 🚨 트러블슈팅

### 문제 1: `HikariPool - Connection is not available`

**증상:**
```
java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms.
```

**원인:**
- DB 커넥션 풀이 부족함
- 모든 커넥션이 사용 중

**해결:**
```yaml
hikari:
  maximum-pool-size: 100  # 50 → 100으로 증가
  connection-timeout: 60000  # 30초 → 60초로 증가
```

---

### 문제 2: `Too many connections` (MySQL 에러)

**증상:**
```
com.mysql.cj.jdbc.exceptions.CommunicationsException: Too many connections
```

**원인:**
- RDS의 `max_connections` 초과
- 여러 WAS 인스턴스가 동시에 연결

**해결:**

1. **RDS 파라미터 그룹 수정:**
```sql
-- RDS 콘솔에서 파라미터 그룹 수정
max_connections = 200  (기본값: 85)
```

2. **각 WAS의 커넥션 풀 조정:**
```yaml
# WAS 2대 운영 시
hikari:
  maximum-pool-size: 80  # 총 160개 (200 이하)
```

**계산 공식:**
```
각 WAS의 maximum-pool-size × WAS 인스턴스 수 < RDS max_connections
예: 80 × 2 = 160 < 200 ✅
```

---

### 문제 3: `OutOfMemoryError: unable to create new native thread`

**증상:**
```
java.lang.OutOfMemoryError: unable to create new native thread
```

**원인:**
- Tomcat 스레드 수가 너무 많음
- EC2 인스턴스 메모리 부족

**해결:**

1. **스레드 수 감소:**
```yaml
server:
  tomcat:
    threads:
      max: 300  # 500 → 300으로 감소
```

2. **EC2 인스턴스 타입 업그레이드:**
```
t3.micro (1GB) → t3.small (2GB) → t3.medium (4GB)
```

---

### 문제 4: 응답 속도 느림 (5초 이상)

**증상:**
- API 응답 시간이 5초 이상
- CloudWatch에서 CPU는 낮은데 느림

**원인:**
- DB 쿼리 성능 문제
- 인덱스 부재

**해결:**

1. **쿼리 최적화:**
```sql
-- users 테이블에 인덱스 추가
CREATE INDEX idx_username ON users(username);

-- ranking 쿼리 최적화
CREATE INDEX idx_points ON users(points DESC);
```

2. **쿼리 로그 확인:**
```yaml
spring:
  jpa:
    show-sql: true  # 쿼리 로그 출력
    properties:
      hibernate:
        format_sql: true
        use_sql_comments: true
```

---

## 📈 모니터링 지표

### 확인해야 할 지표

#### Tomcat 지표
```
- Active Threads: 현재 사용 중인 스레드 수
- Max Threads: 최대 스레드 수 (설정값)
- Current Thread Busy: 사용률 (%)
```

**경고 기준:**
- Active Threads > Max Threads × 0.8 → 스레드 부족

#### HikariCP 지표
```
- Active Connections: 현재 사용 중인 커넥션 수
- Idle Connections: 대기 중인 커넥션 수
- Total Connections: 전체 커넥션 수
- Pending Threads: 커넥션 대기 중인 스레드 수
```

**경고 기준:**
- Active Connections > Maximum Pool Size × 0.8 → 커넥션 부족
- Pending Threads > 0 → 커넥션 대기 발생

---

## 🎯 부하 테스트 전 체크리스트

### 설정 확인
- [ ] Tomcat max threads: 500 이상
- [ ] HikariCP maximum-pool-size: 100 이상
- [ ] RDS max_connections: 200 이상
- [ ] EC2 인스턴스 타입: t3.small 이상 (2GB RAM)

### 모니터링 준비
- [ ] CloudWatch 대시보드 열어두기
- [ ] RDS Performance Insights 활성화
- [ ] 애플리케이션 로그 확인 가능 상태

### 백업
- [ ] RDS 스냅샷 생성
- [ ] 현재 설정 파일 백업

---

## 💡 추가 최적화 팁

### 1. JVM 힙 메모리 설정

**User Data 스크립트에 추가:**
```bash
#!/bin/bash
export JAVA_OPTS="-Xms1024m -Xmx2048m -XX:+UseG1GC"
java $JAVA_OPTS -jar /app/race-1.0.war
```

### 2. Tomcat 압축 활성화

```yaml
server:
  compression:
    enabled: true
    mime-types: application/json,text/html,text/css,application/javascript
    min-response-size: 1024
```

### 3. DB 쿼리 캐싱

```yaml
spring:
  jpa:
    properties:
      hibernate:
        cache:
          use_second_level_cache: true
          region:
            factory_class: org.hibernate.cache.jcache.JCacheRegionFactory
```

---

이제 JMeter 테스트 파일과 설정 가이드가 모두 준비되었습니다! 🎉
