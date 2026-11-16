@echo off
rem Erhoeht die Versionsnummer: version.bat patch | minor | major   (ohne Angabe: patch)
rem Traegt danach einen neuen Abschnitt in CHANGELOG.md ein.
cd /d "%~dp0"
set PART=%~1
if "%PART%"=="" set PART=patch
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$p='gradle.properties'; $t=Get-Content $p -Raw;" ^
  "if($t -notmatch 'mod_version=(\d+)\.(\d+)\.(\d+)'){ Write-Host 'mod_version fehlt in gradle.properties'; exit 1 }" ^
  "$a=[int]$Matches[1];$b=[int]$Matches[2];$c=[int]$Matches[3];" ^
  "switch('%PART%'){ 'major'{$a++;$b=0;$c=0} 'minor'{$b++;$c=0} default{$c++} };" ^
  "$v=\"$a.$b.$c\"; $t=$t -replace 'mod_version=\d+\.\d+\.\d+',\"mod_version=$v\"; Set-Content $p $t -NoNewline;" ^
  "$d=Get-Date -Format 'yyyy-MM-dd'; $cl=[IO.File]::ReadAllText((Resolve-Path 'CHANGELOG.md'), [Text.Encoding]::UTF8);" ^
  "$entry=\"## [$v] - $d`r`n`r`n### Added`r`n- `r`n`r`n### Fixed`r`n- `r`n`r`n\";" ^
  "$i=$cl.IndexOf('## ['); if($i -lt 0){$cl=$cl+\"`r`n\"+$entry}else{$cl=$cl.Insert($i,$entry)}; [IO.File]::WriteAllText((Resolve-Path 'CHANGELOG.md'), $cl, (New-Object Text.UTF8Encoding $false));" ^
  "Write-Host \"Neue Version: $v  (CHANGELOG.md ausfuellen, dann build-mod.bat)\""
pause
