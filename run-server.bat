@echo off
cd /d "C:\Users\31001\Documents\deepseek-harness\default-workspace\gpuaccel-forge"
set "JAVA_HOME=C:\Users\31001\Documents\deepseek-harness\default-workspace\tools\jdk17\jdk-17.0.20.1+1"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "GRADLE_USER_HOME=C:\Users\31001\Documents\deepseek-harness\default-workspace\.gradle-fg"
set "JAVA_TOOL_OPTIONS=-javaagent:C:\Users\31001\Documents\deepseek-harness\default-workspace\agent-build\zipfs-agent.jar"
call gradlew.bat runServer --no-daemon --console=plain > run-server.log 2>&1
echo === RUNSERVER EXIT: %ERRORLEVEL% ===