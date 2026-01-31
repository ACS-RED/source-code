# JMeter 부하 테스트 가이드

## 📦 JMeter 설치

### Windows
```bash
# Chocolatey 사용
choco install jmeter

# 또는 수동 설치
# 1. https://jmeter.apache.org/download_jmeter.cgi 에서 다운로드
# 2. 압축 해제
# 3. bin/jmeter.bat 실행
```

### Mac
```bash
brew install jmeter
```

### Linux
```bash
# Ubuntu/Debian
sudo apt-get install jmeter

# 또는 수동 설치
wget https://dlcdn.apache.org//jmeter/binaries/apache-jmeter-5.6.3.tgz
tar -xzf apache-jmeter-5.6.3.tgz
cd apache-jmeter-5.6.3/bin
./jmeter
```

---

## 🚀 테스트 실행 방법

### 1. GUI 모드 (테스트 설정 및 디버깅용)

```bash
# JMeter GUI 실행
jmeter

# 또는 테스트 파일 직접 열기
jmeter -t jmeter-load-test.jmx
```

**GUI에서 설정 변경:**
1. `Test Plan` → `User Defined Variables` 클릭
2. `SERVER` 변수를 당신의 ALB DNS로 변경
   ```
   예: my-alb-123456.ap-northeast-2.elb.amazonaws.com
   ```
3. 상단 메뉴: `Run` → `Start` (Ctrl+R)

---

### 2. CLI 모드 (실제 부하 테스트용) ⭐ 추천

```bash
# 기본 실행
jmeter -n -t jmeter-load-test.jmx -l results.jtl

# 서버 주소 오버라이드
jmeter -n -t jmeter-load-test.jmx \
  -JSERVER=my-alb-123456.ap-northeast-2.elb.amazonaws.com \
  -l results.jtl \
  -e -o report

# 옵션 설명:
# -n : CLI 모드 (GUI 없이)
# -t : 테스트 파일 경로
# -JSERVER : 서버 주소 변수 오버라이드
# -l : 결과 저장 파일
# -e : 테스트 후 HTML 리포트 생성
# -o : HTML 리포트 저장 폴더
```

---

## 📊 테스트 시나리오 설명

### Thread Group 1: 베팅 공격 유저 (CPU 부하) 🔥
- **유저 수**: 200명
- **Ramp-up**: 60초 (1분에 걸쳐 접속)
- **Duration**: 1800초 (30분)
- **행동 패턴**:
  1. 로그인 (`POST /api/user/create`)
  2. 0.3초마다 랜덤 베팅 (`POST /api/bet`)
     - 랜덤 말 선택 (1~4)
     - 랜덤 금액 (10~100P)

**예상 부하:**
- 200명 × 3.3회/초 = **약 660 RPS**
- DB 쓰기: 1320 TPS (UPDATE + INSERT)
- **CPU 사용률: 50~70%** (예상)

---

### Thread Group 2: 폴링 유저 (실제 패턴)
- **유저 수**: 150명
- **Ramp-up**: 60초
- **Duration**: 1800초 (30분)
- **행동 패턴**:
  - 1초마다 상태 조회 (`GET /api/status`)

**예상 부하:**
- 150명 × 1회/초 = **150 RPS**
- 가벼운 조회 (CPU 거의 안 씀)

---

### Thread Group 3: 랭킹 조회 유저 (DB 부하)
- **유저 수**: 50명
- **Ramp-up**: 30초
- **Duration**: 1800초 (30분)
- **행동 패턴**:
  - 2초마다 랭킹 조회 (`GET /api/ranking`)

**예상 부하:**
- 50명 × 0.5회/초 = **25 RPS**
- ORDER BY 연산으로 DB CPU 사용

---

## 📈 총 예상 부하

| 항목 | 값 |
|------|-----|
| **총 동시 접속자** | 400명 |
| **총 RPS** | 835 RPS |
| **베팅 RPS** | 660 RPS (무거움) |
| **조회 RPS** | 175 RPS (가벼움) |
| **예상 CPU** | 60~80% |
| **Auto Scaling** | 발동 예상 (Target 50% 기준) |

---

## 🎯 Auto Scaling 테스트 시나리오

### 단계별 부하 조절

#### 1단계: 워밍업 (0~5분)
```bash
# Thread Group 1만 활성화, 50명으로 줄이기
jmeter -n -t jmeter-load-test.jmx -l warmup.jtl
```
- **예상 CPU**: 20~30%
- **목적**: 서버 준비 상태 확인

#### 2단계: 부하 증가 (5~15분)
```bash
# 모든 Thread Group 활성화
jmeter -n -t jmeter-load-test.jmx -l load.jtl
```
- **예상 CPU**: 60~80%
- **목적**: Scale-out 트리거

#### 3단계: 안정화 확인 (15~25분)
- **예상 결과**: 인스턴스 2대로 증가
- **예상 CPU**: 30~40% (분산됨)
- **목적**: 부하 분산 확인

#### 4단계: Scale-in 테스트 (25~35분)
```bash
# Thread Group 1의 유저 수를 50명으로 줄이기
# (GUI에서 수정 후 재실행)
```
- **예상 CPU**: 10~20%
- **목적**: Scale-in 트리거 (10분 후)

---

## 📊 결과 분석

### 1. CLI 실행 중 실시간 모니터링

```bash
# 다른 터미널에서 결과 파일 실시간 확인
tail -f results.jtl
```

### 2. HTML 리포트 확인

```bash
# 테스트 완료 후 리포트 폴더 열기
cd report
# index.html을 브라우저로 열기
```

**리포트 주요 지표:**
- **Throughput**: 초당 처리 요청 수 (RPS)
- **Average Response Time**: 평균 응답 시간
- **Error %**: 에러율 (5% 이하 권장)
- **90th Percentile**: 90%의 요청이 이 시간 안에 처리됨

### 3. AWS CloudWatch 확인

**모니터링 항목:**
- **EC2 CPU Utilization**: 60% 이상 → Scale-out 발동
- **ALB Request Count**: RPS 확인
- **RDS CPU Utilization**: DB 병목 확인
- **Auto Scaling Activity**: 인스턴스 증감 이력

---

## ⚙️ 테스트 커스터마이징

### 부하 강도 조절

#### 더 강한 부하 (CPU 80% 이상)
```xml
<!-- jmeter-load-test.jmx 수정 -->
Thread Group 1: 200명 → 300명
Thread Group 2: 150명 → 200명
Thread Group 3: 50명 → 100명
```

#### 더 약한 부하 (CPU 30~40%)
```xml
Thread Group 1: 200명 → 100명
Thread Group 2: 150명 → 50명
Thread Group 3: 50명 → 20명
```

### 베팅 빈도 조절

```xml
<!-- 0.3초 → 0.5초로 변경 (부하 감소) -->
<ConstantTimer>
  <stringProp name="ConstantTimer.delay">500</stringProp>
</ConstantTimer>

<!-- 0.3초 → 0.1초로 변경 (부하 증가) -->
<ConstantTimer>
  <stringProp name="ConstantTimer.delay">100</stringProp>
</ConstantTimer>
```

---

## 🚨 주의사항

### 1. 테스트 전 확인사항
- [ ] ALB DNS 주소 정확히 입력
- [ ] DB 백업 완료 (테스트 데이터로 오염됨)
- [ ] CloudWatch 알람 설정 (비용 폭탄 방지)
- [ ] Auto Scaling 정책 확인 (Max Instances 제한)

### 2. 비용 관리
```
예상 비용 (30분 테스트):
- EC2 t3.medium 2대: $0.10
- RDS db.t3.micro: $0.05
- ALB: $0.02
- 데이터 전송: $0.01
총: 약 $0.18 (250원)
```

### 3. 테스트 중단
```bash
# Ctrl+C로 중단
# 또는 강제 종료
pkill -9 jmeter
```

### 4. 테스트 후 정리
```bash
# 테스트 유저 삭제 (DB 정리)
mysql -h your-rds-endpoint -u admin -p
DELETE FROM users WHERE username LIKE 'user%';
DELETE FROM bets;
```

---

## 📝 체크리스트

### 테스트 전
- [ ] JMeter 설치 완료
- [ ] `jmeter-load-test.jmx` 파일의 SERVER 변수 수정
- [ ] AWS 콘솔에서 CloudWatch 대시보드 열어두기
- [ ] Auto Scaling 정책 확인 (Target: 50%, Max: 4대)

### 테스트 중
- [ ] CloudWatch에서 CPU 사용률 모니터링
- [ ] Auto Scaling Activity 확인
- [ ] JMeter Summary Report에서 에러율 확인 (5% 이하)
- [ ] 응답 시간 확인 (1초 이하 권장)

### 테스트 후
- [ ] HTML 리포트 생성 및 저장
- [ ] CloudWatch 스크린샷 캡처 (발표 자료용)
- [ ] Scale-out/in 이벤트 로그 확인
- [ ] DB 테스트 데이터 정리

---

## 🎓 발표 자료용 스크린샷

### 캡처할 화면
1. **JMeter Summary Report**: Throughput, Response Time
2. **CloudWatch CPU Utilization**: Scale-out 전후 비교
3. **Auto Scaling Activity**: 인스턴스 증가 이벤트
4. **ALB Metrics**: Request Count 그래프
5. **RDS Performance Insights**: DB 부하 확인

---

## 💡 트러블슈팅

### 문제 1: 에러율이 높음 (10% 이상)
**원인**: 서버 과부하 또는 DB 커넥션 부족
**해결**: 
- Thread 수 줄이기
- HikariCP maximum-pool-size 증가 (100 → 200)

### 문제 2: CPU가 안 올라감 (20% 이하)
**원인**: 베팅 요청이 적음
**해결**:
- Thread Group 1의 유저 수 증가 (200 → 400)
- 베팅 간격 단축 (0.3초 → 0.1초)

### 문제 3: Scale-out이 안 됨
**원인**: Target CPU가 너무 높거나 Cooldown 시간 중
**해결**:
- ASG Target을 40%로 낮추기
- 5분 이상 대기 (Cooldown 기본 300초)

### 문제 4: Connection Timeout
**원인**: ALB DNS 주소 오류 또는 Security Group 차단
**해결**:
- ALB DNS 재확인
- Security Group에서 80 포트 허용 확인
