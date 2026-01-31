# Tomcat DB 연결 실패 트러블슈팅

## 🔴 현재 에러 상황

```
HikariPool-1 - Starting...
com.mysql.cj.jdbc.ConnectionImpl.createNewIO
[에러 발생]
```

---

## 🔍 원인 진단 체크리스트

### 1단계: 로그에서 실제 에러 메시지 확인

```bash
# EC2에서 실행
sudo journalctl -u tomcat9 -n 200 --no-pager | grep -E "(Exception|Error|Caused by)" | head -20
```

**찾아야 할 키워드:**
- `Access denied` → DB 인증 실패
- `Communications link failure` → Security Group 문제
- `Unknown database` → DB 이름 오류
- `Connection refused` → RDS 엔드포인트 오류
- `Unable to load AWS credentials` → IAM Role 문제

---

### 2단계: Secrets Manager 연동 확인

#### A. IAM Role 확인
```bash
# EC2에 IAM Role이 연결되어 있는지 확인
curl http://169.254.169.254/latest/meta-data/iam/security-credentials/

# 출력 예시: jeoktoma-ec2-role (있어야 함)
# 출력 없음 → IAM Role 연결 안 됨!
```

**IAM Role이 없으면:**
1. AWS 콘솔 → EC2 → 인스턴스 선택
2. Actions → Security → Modify IAM role
3. 다음 정책이 있는 Role 선택:
   - `SecretsManagerReadWrite`
   - 또는 커스텀 정책:
   ```json
   {
     "Version": "2012-10-17",
     "Statement": [
       {
         "Effect": "Allow",
         "Action": [
           "secretsmanager:GetSecretValue",
           "secretsmanager:DescribeSecret"
         ],
         "Resource": "arn:aws:secretsmanager:ap-northeast-2:*:secret:jeoktoma-db-*"
       }
     ]
   }
   ```

#### B. Secrets Manager 시크릿 확인
```bash
# AWS CLI로 시크릿 값 확인
aws secretsmanager get-secret-value \
  --secret-id jeoktoma-db \
  --region ap-northeast-2 \
  --query SecretString \
  --output text

# 출력 예시:
{"host":"jeoktoma-db.xxxxx.rds.amazonaws.com","port":"3306","dbname":"jeoktoma","username":"admin","password":"your-password"}
```

**에러 발생 시:**
- `AccessDeniedException` → IAM Role 권한 부족
- `ResourceNotFoundException` → 시크릿 이름 오류 (`jeoktoma-db` 확인)

---

### 3단계: Security Group 확인

```bash
# EC2에서 RDS 연결 테스트
telnet your-rds-endpoint.rds.amazonaws.com 3306

# 또는
nc -zv your-rds-endpoint.rds.amazonaws.com 3306
```

**결과 해석:**
- `Connected` → Security Group OK
- `Connection timed out` → Security Group 문제!
- `Connection refused` → RDS 엔드포인트 오류

**해결:**
1. AWS 콘솔 → RDS → 데이터베이스 선택
2. Connectivity & security → Security groups 클릭
3. Inbound rules 편집:
   ```
   Type: MySQL/Aurora
   Protocol: TCP
   Port: 3306
   Source: WAS Security Group ID (sg-xxxxx)
   또는 Private Subnet CIDR (10.10.1.0/24)
   ```

---

### 4단계: DB 직접 접속 테스트

```bash
# MySQL 클라이언트 설치 (없으면)
sudo apt-get update
sudo apt-get install mysql-client -y

# RDS 접속 테스트
mysql -h your-rds-endpoint.rds.amazonaws.com -u admin -p
# 비밀번호 입력

# 성공 시:
mysql> SHOW DATABASES;
mysql> USE jeoktoma;
mysql> SHOW TABLES;
```

**에러 발생 시:**
- `Access denied` → 비밀번호 오류 또는 사용자 권한 문제
- `Unknown database 'jeoktoma'` → DB 생성 안 됨

---

## 🚀 해결 방법

### 방법 1: Secrets Manager 문제 해결 (권장)

#### 1-1. IAM Role 생성 및 연결

**IAM Role 생성:**
```bash
# AWS CLI로 Role 생성
aws iam create-role \
  --role-name jeoktoma-ec2-role \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"Service": "ec2.amazonaws.com"},
      "Action": "sts:AssumeRole"
    }]
  }'

# Secrets Manager 정책 연결
aws iam attach-role-policy \
  --role-name jeoktoma-ec2-role \
  --policy-arn arn:aws:iam::aws:policy/SecretsManagerReadWrite

# Instance Profile 생성
aws iam create-instance-profile \
  --instance-profile-name jeoktoma-ec2-profile

# Role을 Instance Profile에 추가
aws iam add-role-to-instance-profile \
  --instance-profile-name jeoktoma-ec2-profile \
  --role-name jeoktoma-ec2-role

# EC2 인스턴스에 연결
aws ec2 associate-iam-instance-profile \
  --instance-id i-xxxxx \
  --iam-instance-profile Name=jeoktoma-ec2-profile
```

#### 1-2. Tomcat 재시작
```bash
sudo systemctl restart tomcat9
sudo journalctl -u tomcat9 -f
```

---

### 방법 2: Secrets Manager 없이 직접 연결 (임시 해결)

**application.yml 수정:**

```yaml
spring:
  # Secrets Manager 주석 처리
  # config:
  #   import: aws-secretsmanager:jeoktoma-db

  datasource:
    # 직접 값 입력
    url: jdbc:mysql://jeoktoma-db.xxxxx.ap-northeast-2.rds.amazonaws.com:3306/jeoktoma?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
    username: admin
    password: your-actual-password
    driver-class-name: com.mysql.cj.jdbc.Driver
```

**재배포:**
```bash
# 로컬에서 빌드
mvn clean package -DskipTests

# EC2로 전송
scp target/race-1.0.war root@your-ec2-ip:/tmp/

# EC2에서 배포
sudo systemctl stop tomcat9
sudo rm -rf /var/lib/tomcat9/webapps/ROOT*
sudo cp /tmp/race-1.0.war /var/lib/tomcat9/webapps/ROOT.war
sudo systemctl start tomcat9
```

---

### 방법 3: User Data로 환경 변수 주입

**User Data 스크립트:**
```bash
#!/bin/bash

# RDS 정보를 환경 변수로 설정
export DB_HOST="jeoktoma-db.xxxxx.ap-northeast-2.rds.amazonaws.com"
export DB_PORT="3306"
export DB_NAME="jeoktoma"
export DB_USER="admin"
export DB_PASS="your-password"

# Tomcat 환경 변수 파일 생성
cat > /etc/default/tomcat9 << EOF
DB_HOST=${DB_HOST}
DB_PORT=${DB_PORT}
DB_NAME=${DB_NAME}
DB_USER=${DB_USER}
DB_PASS=${DB_PASS}
EOF

# Tomcat 재시작
systemctl restart tomcat9
```

**application.yml 수정:**
```yaml
spring:
  datasource:
    url: jdbc:mysql://${DB_HOST:localhost}:${DB_PORT:3306}/${DB_NAME:jeoktoma}?useSSL=false&serverTimezone=UTC
    username: ${DB_USER:admin}
    password: ${DB_PASS:password}
```

---

## 🔧 추가 디버깅 명령어

### 1. Tomcat 로그 실시간 확인
```bash
sudo journalctl -u tomcat9 -f
```

### 2. application.yml이 제대로 배포되었는지 확인
```bash
sudo unzip -p /var/lib/tomcat9/webapps/ROOT.war WEB-INF/classes/application.yml
```

### 3. HikariCP 연결 정보 확인
```bash
# 로그에서 JDBC URL 확인
sudo journalctl -u tomcat9 --no-pager | grep "jdbc:mysql"
```

### 4. Secrets Manager 값이 제대로 로드되는지 확인
```bash
# Spring Boot 시작 로그에서 확인
sudo journalctl -u tomcat9 --no-pager | grep -i "secret"
```

### 5. DB 테이블 존재 확인
```bash
mysql -h your-rds-endpoint -u admin -p -e "USE jeoktoma; SHOW TABLES;"
```

---

## 📋 체크리스트

### Secrets Manager 사용 시
- [ ] EC2에 IAM Role 연결됨
- [ ] IAM Role에 `SecretsManagerReadWrite` 정책 있음
- [ ] Secrets Manager에 `jeoktoma-db` 시크릿 존재
- [ ] 시크릿 값이 올바른 JSON 형식
- [ ] Region이 일치 (ap-northeast-2)

### Security Group
- [ ] RDS Security Group Inbound에 WAS Security Group 허용
- [ ] Port 3306 허용
- [ ] `telnet` 또는 `nc` 테스트 성공

### DB 설정
- [ ] RDS 엔드포인트 정확함
- [ ] DB 이름 `jeoktoma` 존재
- [ ] 사용자 `admin` 권한 있음
- [ ] 비밀번호 정확함

### 애플리케이션
- [ ] `pom.xml`에 `spring-cloud-starter-aws-secrets-manager-config` 의존성 있음
- [ ] `application.yml`에 `spring.config.import` 설정됨
- [ ] WAR 파일이 최신 버전으로 배포됨

---

## 💡 가장 빠른 해결 방법

**1. 먼저 Security Group 확인:**
```bash
nc -zv your-rds-endpoint.rds.amazonaws.com 3306
```

**2. 연결 안 되면 → Security Group 수정**

**3. 연결 되면 → Secrets Manager 문제:**
```bash
# IAM Role 확인
curl http://169.254.169.254/latest/meta-data/iam/security-credentials/
```

**4. IAM Role 없으면 → 방법 2 (직접 연결)로 임시 해결**

**5. 나중에 Secrets Manager 재설정**

---

## 🎯 발표 자료용 트러블슈팅 스토리

**문제:**
- Tomcat 시작 시 HikariCP가 DB 연결 실패

**원인:**
- EC2 인스턴스에 IAM Role이 연결되지 않아 Secrets Manager 접근 불가
- 또는 RDS Security Group에서 WAS 접근 차단

**해결:**
1. IAM Role 생성 및 EC2에 연결
2. RDS Security Group Inbound Rule 추가
3. Tomcat 재시작으로 정상 작동 확인

**교훈:**
- 클라우드 환경에서는 네트워크(Security Group)와 권한(IAM)이 핵심
- Secrets Manager 사용 시 IAM Role 필수
- 단계별 디버깅으로 문제 원인 빠르게 파악
