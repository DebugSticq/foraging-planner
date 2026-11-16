@echo off
rem Builds the mod and opens the folder with the finished jar. Output goes to build-log.txt.
cd /d "%~dp0"
set LOG=%~dp0build-log.txt
echo === build started %date% %time% > "%LOG%"

set JDK=
for /d %%D in ("%USERPROFILE%\.jdks\*25*" "%ProgramFiles%\Java\jdk-25*" "%ProgramFiles%\Eclipse Adoptium\jdk-25*" "%ProgramFiles%\Microsoft\jdk-25*" "%ProgramFiles%\Zulu\zulu-25*" "%ProgramFiles%\Amazon Corretto\jdk25*") do (
	if exist "%%~D\bin\javac.exe" set JDK=%%~D
)

echo --- installed JDK folders >> "%LOG%"
dir /b "%USERPROFILE%\.jdks" >> "%LOG%" 2>&1
dir /b "%ProgramFiles%\Java" >> "%LOG%" 2>&1
dir /b "%ProgramFiles%\Eclipse Adoptium" >> "%LOG%" 2>&1
dir /b "%ProgramFiles%\Microsoft" 2>nul | findstr /i jdk >> "%LOG%"
echo --- java on PATH >> "%LOG%"
java -version >> "%LOG%" 2>&1

if "%JDK%"=="" (
	echo RESULT: NO_JDK25 >> "%LOG%"
	exit /b 2
)

echo --- using JDK %JDK% >> "%LOG%"
set JAVA_HOME=%JDK%
call gradlew.bat build --console=plain >> "%LOG%" 2>&1

if errorlevel 1 (
	echo RESULT: BUILD_FAILED >> "%LOG%"
	exit /b 1
)

echo RESULT: BUILD_OK >> "%LOG%"
for %%F in ("%~dp0build\libs\foraging-planner-*.jar") do echo JAR: %%~nxF >> "%LOG%"
start "" "%~dp0build\libs"
