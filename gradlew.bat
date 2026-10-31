@echo off
setlocal
set "EMBERLILY_ROOT=%~dp0"
set "EMBERLILY_JAVA=java.exe"
if defined JAVA_HOME set "EMBERLILY_JAVA=%JAVA_HOME%\bin\java.exe"
"%EMBERLILY_JAVA%" -Dorg.gradle.appname=gradlew -classpath "%EMBERLILY_ROOT%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
