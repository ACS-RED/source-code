# HikariCP vs RDS Proxy 비교

## 🔍 핵심 차이점

### HikariCP (애플리케이션 레벨)
```
[WAS 1]                    [RDS]
┌─────────────┐           ┌──────┐
│ HikariCP    │──100개──→ │MySQL │
│ (100개 풀)  │           │      │
└─────────────┘           └──────┘

[WAS 2]
┌─────────────┐
│ HikariCP    │──100개──→ (같은 RDS)
│ (100개 풀)  │
└─────────────┘

총 RDS 연결: 200개
```

### RDS Proxy (인프라 레벨)
```
[WAS 1]                [RDS Proxy]        [RDS]
┌─────────────┐       ┌──────────┐       ┌──────┐
│ HikariCP    │──100→ │          │       │MySQL │
│ (100개 풀)  │       │  커넥션  │──50→  │      │
└─────────────┘       │   풀링   │       └──────┘
                      │          │
[WAS 2]               │          │
┌─────────────┐       │          │
│ HikariCP    │──100→ │          │
│ (100개 풀)  │       └──────────┘
└─────────────┘

WAS → Proxy: 200개
Proxy → RDS: 50개 (재사용!)
```

---

## 📊 상세 비교

| 항목 | HikariCP | RDS Proxy |
|------|----------|-----------|
| **위치** | WAS 내부 (애플리케이션) | AWS 인프라 (RDS 앞단) |
| **범위** | 각 WAS 인스턴스마다 독립적 | 모든 WAS가 공유 |
| **연결 관리** | WAS ↔ RDS | WAS ↔ Proxy ↔ RDS |
| **비용** | 무료 (라이브러리) | 유료 (시간당 $0.015) |
| **설정** | application.yml | AWS 콘솔 |
| **Failover** | 수동 (30초~1분) | 자동 (1초 이내) |
| **Lambda 지원** | ❌ (Lambda는 매번 새 연결) | ✅ (연결 재사용) |

---

## 🎯 각각의 역할

### HikariCP의 역할
```
목적: WAS 내부에서 DB 연결 재사용

[요청 처리 흐름]
1. HTTP 요청 도착
2. Tomcat 스레드가 처리
3. HikariCP에서 연결 빌림 (0.001초)
4. 쿼리 실행 (0.01초)
5. 연결 반납
6. 응답 반환

효과:
- 매번 DB 연결 생성 안 함 (0.3초 절약)
- 단일 WAS 내부 최적화
```

### RDS Proxy의 역할
```
목적: 여러 WAS의 연결을 통합 관리

[연결 풀링]
WAS 1: 100개 연결 요청
WAS 2: 100개 연결 요청
WAS 3: 100개 연결 요청
↓
RDS Proxy: 300개 받아서 → RDS에는 50개만 연결
↓
RDS 부하 감소!

효과:
- RDS max_connections 절약
- 자동 Failover (Multi-AZ)
- IAM 인증 지원
```

---

## 🔄 함께 사용하는 경우

### 아키텍처
```
[WAS 1]                [RDS Proxy]              [RDS Primary]
┌─────────────┐       ┌──────────────┐         ┌──────────┐
│ HikariCP    │       │              │         │ MySQL    │
│ max: 100    │──┐    │  Connection  │    ┌──→ │ Master   │
│ min: 20     │  │    │  Pooling     │    │    └──────────┘
└─────────────┘  │    │              │    │
                 ├──→ │  Multiplexing│────┤    [RDS Replica]
[WAS 2]          │    │              │    │    ┌──────────┐
┌─────────────┐  │    │  Auto        │    └──→ │ MySQL    │
│ HikariCP    │  │    │  Failover    │         │ Read     │
│ max: 100    │──┘    │              │         └──────────┘
└─────────────┘       └──────────────┘

WAS → Proxy: 200개 연결
Proxy → RDS: 30개 연결 (효율적!)
```

### application.yml 설정 (RDS Proxy 사용 시)
```yaml
spring:
  datasource:
    # RDS 직접 연결 (기존)
    # url: jdbc:mysql://jeoktoma-db.xxxxx.rds.amazonaws.com:3306/jeoktoma
    
    # RDS Proxy 연결 (변경)
    url: jdbc:mysql://jeoktoma-proxy.proxy-xxxxx.ap-northeast-2.rds.amazonaws.com:3306/jeoktoma
    
    hikari:
      maximum-pool-size: 100  # WAS 내부 풀
      minimum-idle: 20
```

**변경 사항:**
- JDBC URL만 RDS Proxy 엔드포인트로 변경
- HikariCP 설정은 그대로 유지
- WAS는 Proxy와 통신하는 줄 모름 (투명함)

---

## 💰 비용 비교

### HikariCP만 사용 (현재)
```
비용: $0 (무료 라이브러리)

WAS 2대 × 100개 연결 = RDS 200개 연결
→ RDS db.t3.small 필요 (max_connections: 150 부족!)
→ RDS db.t3.medium 필요 ($0.068/시간)

월 비용: $50
```

### RDS Proxy + HikariCP
```
RDS Proxy: $0.015/시간 × 730시간 = $10.95/월
RDS db.t3.micro: $0.017/시간 × 730시간 = $12.41/월
(Proxy가 연결 줄여줘서 작은 인스턴스 가능)

월 비용: $23.36

절약: $50 - $23.36 = $26.64/월
```

**하지만:**
- WAS 2대 정도면 HikariCP만으로 충분
- WAS 10대 이상이면 RDS Proxy 고려

---

## 🚀 언제 RDS Proxy를 사용하나?

### ✅ RDS Proxy가 필요한 경우

#### 1. Lambda 함수가 DB 접근
```
문제:
Lambda 동시 실행 100개
→ 각각 새 DB 연결 생성
→ RDS max_connections 초과!

해결:
Lambda → RDS Proxy → RDS
→ Proxy가 연결 재사용
→ RDS 연결 10개만 사용
```

#### 2. WAS 인스턴스가 많음 (10대 이상)
```
WAS 10대 × 100개 = 1000개 연결
→ RDS max_connections 부족

RDS Proxy 사용:
WAS 10대 → Proxy → RDS 100개 연결
```

#### 3. 빠른 Failover 필요
```
RDS Multi-AZ Failover:
- 직접 연결: 30초~1분
- RDS Proxy: 1초 이내 (자동 재연결)
```

#### 4. IAM 인증 사용
```
RDS Proxy:
- 비밀번호 없이 IAM Role로 인증
- 15분마다 자동 토큰 갱신
```

---

### ❌ RDS Proxy가 불필요한 경우 (당신의 경우!)

#### 1. WAS 인스턴스가 적음 (2~4대)
```
WAS 2대 × 100개 = 200개 연결
→ RDS db.t3.small (max_connections: 150)로 충분
→ Proxy 없이도 관리 가능
```

#### 2. Lambda 사용 안 함
```
Lambda가 없으면 Proxy의 주요 장점 활용 못 함
```

#### 3. 비용 절약 우선
```
RDS Proxy: $10.95/월 추가 비용
→ 학습/발표 프로젝트에는 과함
```

---

## 🎓 발표 자료에 포함할 내용

### 현재 아키텍처 (HikariCP만 사용)
```
장점:
✅ 무료 (라이브러리)
✅ 설정 간단 (application.yml)
✅ WAS 2대 정도면 충분
✅ 성능 최적화 효과 큼

단점:
❌ WAS마다 독립적인 풀 (통합 관리 안 됨)
❌ RDS max_connections 제약
❌ Failover 시간 30초~1분
```

### RDS Proxy 추가 시 (선택 사항)
```
장점:
✅ 여러 WAS의 연결 통합 관리
✅ RDS 연결 수 대폭 감소
✅ 자동 Failover (1초 이내)
✅ Lambda 지원

단점:
❌ 추가 비용 ($10.95/월)
❌ 약간의 레이턴시 증가 (1~2ms)
❌ 설정 복잡도 증가
```

### 결론
```
현재 프로젝트:
- WAS 2~4대 예상
- Lambda 사용 안 함
- 학습/발표 목적

→ HikariCP만으로 충분! ✅
→ RDS Proxy는 "향후 확장 시 고려 사항"으로 언급
```

---

## 📋 설정 비교

### HikariCP만 사용 (현재 설정)
```yaml
spring:
  datasource:
    url: jdbc:mysql://jeoktoma-db.xxxxx.rds.amazonaws.com:3306/jeoktoma
    hikari:
      maximum-pool-size: 100
      minimum-idle: 20
```

**RDS 연결:**
- WAS 1: 100개
- WAS 2: 100개
- 총: 200개

---

### RDS Proxy 추가 시
```yaml
spring:
  datasource:
    # URL만 변경
    url: jdbc:mysql://jeoktoma-proxy.proxy-xxxxx.ap-northeast-2.rds.amazonaws.com:3306/jeoktoma
    hikari:
      maximum-pool-size: 100  # 그대로 유지
      minimum-idle: 20
```

**연결 흐름:**
- WAS 1 → Proxy: 100개
- WAS 2 → Proxy: 100개
- Proxy → RDS: 50개 (Proxy가 줄여줌)

---

## 🔧 RDS Proxy 설정 방법 (참고용)

### 1. RDS Proxy 생성
```bash
# AWS CLI
aws rds create-db-proxy \
  --db-proxy-name jeoktoma-proxy \
  --engine-family MYSQL \
  --auth '{"AuthScheme":"SECRETS","SecretArn":"arn:aws:secretsmanager:..."}' \
  --role-arn arn:aws:iam::123456789012:role/RDSProxyRole \
  --vpc-subnet-ids subnet-xxxxx subnet-yyyyy
```

### 2. Target Group 등록
```bash
aws rds register-db-proxy-targets \
  --db-proxy-name jeoktoma-proxy \
  --db-instance-identifiers jeoktoma-db
```

### 3. application.yml 수정
```yaml
spring:
  datasource:
    url: jdbc:mysql://jeoktoma-proxy.proxy-xxxxx.ap-northeast-2.rds.amazonaws.com:3306/jeoktoma
```

### 4. Security Group 수정
```
WAS Security Group → RDS Proxy Security Group (3306)
RDS Proxy Security Group → RDS Security Group (3306)
```

---

## 💡 요약

### HikariCP (필수)
- **위치**: WAS 내부
- **역할**: 단일 WAS의 DB 연결 재사용
- **비용**: 무료
- **당신의 프로젝트**: ✅ 사용 중

### RDS Proxy (선택)
- **위치**: RDS 앞단
- **역할**: 여러 WAS의 연결 통합 관리
- **비용**: $10.95/월
- **당신의 프로젝트**: ❌ 불필요 (WAS 2대만 사용)

### 결론
```
HikariCP 설정은 RDS에 직접 적용되는 게 아니라,
WAS 내부에서 RDS로 가는 연결을 관리하는 것!

RDS Proxy는 별도의 AWS 서비스로,
필요하면 나중에 추가할 수 있음.

현재는 HikariCP만으로 충분합니다! 👍
```

---

## 🎯 발표 시 언급할 내용

**질문 예상: "RDS Proxy는 왜 안 썼나요?"**

**답변:**
```
"RDS Proxy는 Lambda나 WAS 10대 이상의 대규모 환경에서
연결 수를 줄이기 위한 서비스입니다.

저희 프로젝트는 WAS 2~4대 정도로,
HikariCP의 커넥션 풀만으로도 충분히 효율적으로
DB 연결을 관리할 수 있었습니다.

또한 비용 절감 측면에서도 HikariCP는 무료 라이브러리이고,
RDS Proxy는 월 $10 이상의 추가 비용이 발생하여
현재 규모에서는 불필요하다고 판단했습니다.

향후 서비스가 확장되어 WAS 인스턴스가 10대 이상으로
늘어나거나, Lambda를 도입할 경우 RDS Proxy 적용을
고려할 수 있습니다."
```
