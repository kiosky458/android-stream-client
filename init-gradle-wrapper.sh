#!/bin/bash
# 初始化 Gradle Wrapper（用於 GitHub Actions）

set -e

echo "🔧 初始化 Gradle Wrapper..."

# 下載 gradle-wrapper.jar
GRADLE_VERSION="8.5"
WRAPPER_JAR="gradle/wrapper/gradle-wrapper.jar"
WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v${GRADLE_VERSION}/gradle/wrapper/gradle-wrapper.jar"

mkdir -p gradle/wrapper

echo "📥 下載 gradle-wrapper.jar..."
curl -L -o "$WRAPPER_JAR" "$WRAPPER_URL" 2>/dev/null || \
    wget -O "$WRAPPER_JAR" "$WRAPPER_URL" 2>/dev/null || \
    echo "❌ 下載失敗，請手動下載 gradle-wrapper.jar"

# 生成 gradlew 和 gradlew.bat
cat > gradlew << 'EOF'
#!/bin/sh
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'
APP_BASE_NAME=${0##*/}
APP_HOME=$( cd "${0%/*}" && pwd )
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
exec java $DEFAULT_JVM_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
EOF

cat > gradlew.bat << 'EOF'
@rem Gradle startup script for Windows
@if "%OS%"=="Windows_NT" setlocal
set DIRNAME=%~dp0
set CLASSPATH=%DIRNAME%\gradle\wrapper\gradle-wrapper.jar
java.exe -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
if "%ERRORLEVEL%"=="0" goto mainEnd
:fail
exit /b 1
:mainEnd
if "%OS%"=="Windows_NT" endlocal
EOF

chmod +x gradlew
chmod +x gradlew.bat

echo "✅ Gradle Wrapper 初始化完成"
echo ""
echo "📋 接下來："
echo "  1. 建立 GitHub 倉庫"
echo "  2. git init && git add . && git commit -m 'Initial commit'"
echo "  3. git push -u origin main"
echo "  4. GitHub Actions 將自動編譯 APK"
