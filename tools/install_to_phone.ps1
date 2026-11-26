$ErrorActionPreference = 'Stop'
$Pkg   = 'com.yogacoach.app'
$Tools = Join-Path $env:LOCALAPPDATA 'YogaCoachTools'
$Here  = $PSScriptRoot
$Root  = Split-Path $Here -Parent

function Pause-Exit($code) { Read-Host '엔터를 누르면 창이 닫힙니다'; exit $code }

function Get-Adb {
  $cmd = Get-Command adb -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
  if (Test-Path $sdk) { return $sdk }
  $local = Join-Path $Tools 'platform-tools\adb.exe'
  if (Test-Path $local) { return $local }
  Write-Host 'adb(안드로이드 연결 도구)를 내려받는 중입니다... (약 10MB)'
  New-Item -ItemType Directory -Force -Path $Tools | Out-Null
  $zip = Join-Path $Tools 'platform-tools.zip'
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
  Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip -UseBasicParsing
  Expand-Archive -LiteralPath $zip -DestinationPath $Tools -Force
  Remove-Item $zip -Force
  return $local
}

function Wait-Device($adb) {
  & $adb start-server | Out-Null
  for ($i = 0; $i -lt 60; $i++) {
    $lines = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\S' }
    if ($lines -match '\tdevice$') { return $true }
    if ($i -eq 0 -or $lines -match 'unauthorized') {
      Write-Host ''
      Write-Host '휴대폰 연결을 기다리는 중입니다...' -ForegroundColor Yellow
      Write-Host ' 1) 휴대폰 설정 > 휴대전화 정보 > 소프트웨어 정보 > "빌드번호" 7번 누르기 (개발자 모드)'
      Write-Host ' 2) 설정 > 개발자 옵션 > "USB 디버깅" 켜기'
      Write-Host ' 3) USB 케이블로 PC와 연결 → 휴대폰에 뜨는 "USB 디버깅 허용" 에서 [허용]'
    }
    Start-Sleep -Seconds 2
  }
  return $false
}

Write-Host '=== 요가 휴대폰 설치 ==='
$apk = @((Join-Path $Root 'Yoga.apk'), (Join-Path $Here 'Yoga.apk'),
         (Join-Path $Root 'app\build\outputs\apk\release\app-release.apk')) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $apk) {
  Write-Host 'Yoga.apk 파일을 찾지 못했습니다.' -ForegroundColor Red
  Write-Host 'README 의 "APK 만들기" 를 따라 APK 를 만든 뒤, Yoga.apk 로 이름을 바꿔 이 폴더 바로 위(YogaCoach 폴더)에 두세요.'
  Pause-Exit 1
}
try { $adb = Get-Adb } catch { Write-Host "adb 다운로드 실패: $_" -ForegroundColor Red; Pause-Exit 1 }
if (-not (Wait-Device $adb)) { Write-Host '휴대폰을 찾지 못했습니다 (2분 초과).' -ForegroundColor Red; Pause-Exit 1 }
Write-Host "설치 중: $apk"
& $adb install -r "$apk"
if ($LASTEXITCODE -ne 0) { Write-Host '설치 실패 - 휴대폰 화면에 확인 창이 떴는지 보세요.' -ForegroundColor Red; Pause-Exit 1 }
& $adb shell monkey -p $Pkg -c android.intent.category.LAUNCHER 1 | Out-Null
Write-Host ''
Write-Host '설치 완료! 휴대폰에서 요가가 실행됩니다.' -ForegroundColor Green
Write-Host '처음 실행 시 "홈 화면에 바로가기 추가" 창에서 [추가]를 누르면 홈 화면에 아이콘이 생깁니다.'
Pause-Exit 0
