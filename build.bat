@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion
title 智慧屏短剧 - 一键构建 (华为智慧屏定制版)

echo ============================================================
echo   智慧屏短剧 - 华为智慧屏大屏版 一键构建脚本
echo   基于 N3urda/hongguoTV 内核，面向 HarmonyOS 4.3 智慧屏
echo ============================================================
echo.

cd /d "%~dp0"

REM ---------- 1. 定位 D 盘产出目录下的工具链（用相对路径，避免中文硬编码）----------
echo [1/5] 定位工具链 ...
set "TOOLS=%CD%\..\..\tools"
set "OUTPUTS=%CD%\..\..\outputs"
set "JAVA_HOME=%TOOLS%\jdk-17"
set "ANDROID_HOME=%TOOLS%\android-sdk"
set "ANDROID_SDK_ROOT=%TOOLS%\android-sdk"
set "GRADLE_USER_HOME=%TOOLS%\gradle-home"
set "GRADLE=%TOOLS%\gradle-8.14.3\bin\gradle.bat"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo   [错误] 未找到 JDK 17: %JAVA_HOME%\bin\java.exe
    goto :fail
)
if not exist "%GRADLE%" (
    echo   [错误] 未找到 Gradle 8.14.3: %GRADLE%
    goto :fail
)
if not exist "%ANDROID_HOME%\platforms\android-36\android.jar" (
    echo   [错误] 未找到 Android SDK Platform 36: %ANDROID_HOME%
    goto :fail
)
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set JVER=%%v
echo   Java: !JVER!
echo   SDK:  %ANDROID_HOME%
echo   Gradle: %GRADLE%

REM ---------- 2. 校验配置（local.properties / keystore.properties 已用 \u 转义中文路径）----------
echo [2/5] 校验构建配置 ...
if not exist "local.properties" (
    echo   [错误] 缺少 local.properties，请检查工程完整性。
    goto :fail
)
if not exist "keystore.properties" (
    echo   [错误] 缺少 keystore.properties，请检查工程完整性。
    goto :fail
)
echo   配置就绪。

REM ---------- 3. 校验签名证书 ----------
echo [3/5] 检查签名证书 ...
if not exist "signing\huawei-screen.jks" (
    echo   正在生成签名证书（仅需一次）...
    "%JAVA_HOME%\bin\keytool.exe" -genkeypair -v -keystore signing\huawei-screen.jks -alias huawei-screen -keyalg RSA -keysize 2048 -validity 10000 -storepass changeit -keypass changeit -dname "CN=Huawei Screen DIY, OU=Personal, O=Personal, L=NA, ST=NA, C=CN" >nul 2>&1
    if errorlevel 1 (
        echo   [错误] 签名证书生成失败。
        goto :fail
    )
) else (
    echo   复用已有证书 signing\huawei-screen.jks（务必保留，否则已安装用户需卸载重装）。
)

REM ---------- 4. 编译 Release APK（直接用独立 Gradle，绕开 wrapper 下载超时）----------
echo [4/5] 编译 Release APK ...
call "%GRADLE%" --no-daemon :app:assembleRelease --console=plain
if errorlevel 1 (
    echo   [错误] 编译失败，请查看上方日志或 D:\WorkBuddy产出\logs\ 下的构建日志。
    goto :fail
)

REM ---------- 5. 复制产物到统一 outputs 目录 ----------
echo [5/5] 收集产物 ...
if not exist "%OUTPUTS%" mkdir "%OUTPUTS%"
set "APK=app\build\platform\outputs\apk\release\app-release.apk"
if not exist "!APK!" set "APK=app\build\outputs\apk\release\app-release.apk"
if not exist "!APK!" (
    echo   [错误] 未找到编译产物 APK。
    goto :fail
)
copy /Y "!APK!" "%OUTPUTS%\智慧屏短剧-huawei-screen.apk" >nul
echo.
echo ============================================================
echo   构建成功！
echo   安装包: %OUTPUTS%\智慧屏短剧-huawei-screen.apk
echo.
echo   下一步：把该 APK 拷到 U 盘，插到华为智慧屏上安装。
echo   详细安装步骤见 README.md / 安装说明.md
echo ============================================================
goto :eof

:fail
echo.
echo [构建中止] 请按上面的提示处理后重试。
pause
exit /b 1
