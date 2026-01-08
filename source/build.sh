#!/bin/bash

echo "🐴 적토마 레이스 게임 빌드 시작..."

# Maven 또는 Gradle 빌드 (Maven 예시)
if [ -f "pom.xml" ]; then
    echo "Maven 프로젝트 빌드 중..."
    mvn clean package -DskipTests
    
    if [ $? -eq 0 ]; then
        echo "✅ 빌드 성공!"
        echo "WAR 파일 위치: target/jeoktoma-race.war"
        
        # WAR 파일을 현재 디렉토리로 복사
        cp target/*.war ./jeoktoma-race.war
        
        echo "배포 준비 완료. deploy.sh를 실행하여 배포하세요."
    else
        echo "❌ 빌드 실패!"
        exit 1
    fi
else
    echo "❌ pom.xml을 찾을 수 없습니다. Maven 프로젝트인지 확인하세요."
    exit 1
fi
