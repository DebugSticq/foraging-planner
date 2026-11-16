@echo off
rem Publishes the current version: checks everything is committed, tags v<mod_version>
rem and pushes. GitHub Actions then builds the jar and creates the release (and Modrinth upload).
cd /d "%~dp0"
for /f "tokens=2 delims==" %%V in ('findstr /b "mod_version=" gradle.properties') do set VERSION=%%V
if "%VERSION%"=="" ( echo mod_version not found in gradle.properties & pause & exit /b 1 )
git diff --quiet && git diff --cached --quiet
if errorlevel 1 ( echo There are uncommitted changes. Commit them first. & pause & exit /b 1 )
findstr /c:"## [%VERSION%]" CHANGELOG.md >nul
if errorlevel 1 ( echo CHANGELOG.md has no section for %VERSION%. & pause & exit /b 1 )
git rev-parse "v%VERSION%" >nul 2>&1
if not errorlevel 1 ( echo Tag v%VERSION% already exists. Raise the version with version.bat first. & pause & exit /b 1 )
echo Releasing %VERSION% ...
git push origin HEAD
if errorlevel 1 ( pause & exit /b 1 )
git tag -a "v%VERSION%" -m "Foraging Planner %VERSION%"
git push origin "v%VERSION%"
if errorlevel 1 ( pause & exit /b 1 )
echo Done. GitHub builds the release now: https://github.com/LootingVI/foraging-planner/actions
pause
