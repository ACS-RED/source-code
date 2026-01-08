#!/bin/bash

# 적토마 레이스 게임 배포 스크립트
echo "🐴 적토마 레이스 게임 배포 시작..."

# 변수 설정
APP_NAME="jeoktoma-race"
TOMCAT_HOME="/opt/tomcat"
WAR_FILE="$APP_NAME.war"
BACKUP_DIR="/opt/backup"

# 백업 디렉토리 생성
mkdir -p $BACKUP_DIR

# 기존 애플리케이션 백업
if [ -f "$TOMCAT_HOME/webapps/$WAR_FILE" ]; then
    echo "기존 애플리케이션 백업 중..."
    cp "$TOMCAT_HOME/webapps/$WAR_FILE" "$BACKUP_DIR/${APP_NAME}_$(date +%Y%m%d_%H%M%S).war"
fi

# Tomcat 중지
echo "Tomcat 중지 중..."
sudo systemctl stop tomcat

# 기존 애플리케이션 제거
rm -rf "$TOMCAT_HOME/webapps/$APP_NAME"
rm -f "$TOMCAT_HOME/webapps/$WAR_FILE"

# 새 WAR 파일 배포
echo "새 애플리케이션 배포 중..."
cp "./$WAR_FILE" "$TOMCAT_HOME/webapps/"

# Tomcat 시작
echo "Tomcat 시작 중..."
sudo systemctl start tomcat

# 배포 확인
sleep 10
if curl -f http://localhost:8080/$APP_NAME/api/status > /dev/null 2>&1; then
    echo "✅ 배포 성공! 애플리케이션이 정상 작동 중입니다."
else
    echo "❌ 배포 실패! 롤백을 진행합니다..."
    
    # 롤백
    sudo systemctl stop tomcat
    rm -rf "$TOMCAT_HOME/webapps/$APP_NAME"
    rm -f "$TOMCAT_HOME/webapps/$WAR_FILE"
    
    # 최신 백업 파일 찾기
    LATEST_BACKUP=$(ls -t $BACKUP_DIR/${APP_NAME}_*.war | head -n1)
    if [ -n "$LATEST_BACKUP" ]; then
        cp "$LATEST_BACKUP" "$TOMCAT_HOME/webapps/$WAR_FILE"
        echo "백업에서 복원 완료"
    fi
    
    sudo systemctl start tomcat
    echo "롤백 완료"
fi

echo "배포 스크립트 종료"
