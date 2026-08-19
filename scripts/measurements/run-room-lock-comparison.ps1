param(
	[switch]$CaptureRaw
)

$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$runnerPath = 'scripts/measurements/run-room-lock-comparison.ps1'
$rawArtifactRelativePath = 'docs/measurements/results/room-lock-01-c-raw.log'
$receiptArtifactRelativePath = 'docs/measurements/results/room-lock-01-c-receipt.txt'
$captureStateRelativePath = 'build/room-lock-01-c-capture.state'
$rawArtifactPath = Join-Path $repositoryRoot $rawArtifactRelativePath
$receiptArtifactPath = Join-Path $repositoryRoot $receiptArtifactRelativePath
$captureStatePath = Join-Path $repositoryRoot $captureStateRelativePath
$baseSha = '49b960a1f7537574b39d67ff22df8890a3891ef6'
$actualCommand = '.\gradlew.bat postgresTest --tests "cloud.bamsongi.albammate.room.measurement.RoomLockStrategyComparisonPostgresTest.*" --rerun --no-daemon --stacktrace'
$executionIdBytes = [Text.Encoding]::UTF8.GetBytes($actualCommand)
$executionIdHash = [Security.Cryptography.SHA256]::Create().ComputeHash($executionIdBytes)
$executionId = ([Convert]::ToHexString($executionIdHash)).ToLowerInvariant()

function Get-GitValue {
	param([Parameter(Mandatory = $true)][string[]]$Arguments)

	$value = & git @Arguments
	if ($LASTEXITCODE -ne 0) {
		throw "git 명령이 실패했습니다: git $($Arguments -join ' ')"
	}
	return ($value | Out-String).Trim()
}

function Assert-CaptureWorktreeClean {
	$changes = Get-GitValue -Arguments @('status', '--porcelain=v1', '--untracked-files=all')
	if ([string]::IsNullOrWhiteSpace($changes)) {
		return
	}
	$allowedCaptureOutputPaths = @(
		$rawArtifactRelativePath
		$receiptArtifactRelativePath)
	$unexpectedChanges = @($changes -split "`r?`n" | Where-Object {
		$line = $_.TrimEnd()
		if ([string]::IsNullOrWhiteSpace($line)) {
			return $false
		}
		$pathStart = if ($line.Length -gt 1 -and $line[1] -eq ' ') { 2 } else { 3 }
		$path = $line.Substring($pathStart).Trim().Trim('"')
		return $allowedCaptureOutputPaths -notcontains $path
	})
	if ($unexpectedChanges.Count -gt 0) {
		throw "-CaptureRaw는 승인된 raw/receipt 출력 경로 외의 source/test/runner/build/config 변경을 포함한 dirty worktree에서 실행할 수 없습니다: $($unexpectedChanges -join '; ')"
	}
}

function Write-ExecutionReceipt {
	param(
		[Parameter(Mandatory = $true)][string]$SourceSha,
		[Parameter(Mandatory = $true)][string]$RawDigest,
		[Parameter(Mandatory = $true)][int]$TestCaseCount,
		[Parameter(Mandatory = $true)][string]$DestinationPath)

	$receiptContent = @(
		'candidate=C'
		"sourceSha=$SourceSha"
		"baseSha=$baseSha"
		"headSha=$SourceSha"
		"command=$actualCommand"
		"runner=$runnerPath"
		"executionId=$executionId"
		'result=BUILD_SUCCESSFUL'
		"testCases=$TestCaseCount"
		"rawArtifactDigest=$RawDigest"
	) -join "`n"
	$receiptContent += "`n"
	[IO.File]::WriteAllText($DestinationPath, $receiptContent, (New-Object Text.UTF8Encoding($false)))
}

$previousCommand = $env:ROOM785_ACTUAL_COMMAND
$previousRunnerPath = $env:ROOM785_RUNNER_PATH
$previousExecutionId = $env:ROOM785_EXECUTION_ID
$previousRawArtifactPath = $env:ROOM785_RAW_ARTIFACT_PATH
$previousCaptureRaw = $env:ROOM785_CAPTURE_RAW
$temporaryRawArtifactPath = $null
$temporaryReceiptPath = $null
$sourceShaBeforeCapture = $null
try {
	$env:ROOM785_ACTUAL_COMMAND = $actualCommand
	$env:ROOM785_RUNNER_PATH = $runnerPath
	$env:ROOM785_EXECUTION_ID = $executionId
	$env:ROOM785_RAW_ARTIFACT_PATH = $rawArtifactRelativePath
	if ($CaptureRaw) {
		$env:ROOM785_CAPTURE_RAW = 'true'
	} else {
		Remove-Item Env:ROOM785_CAPTURE_RAW -ErrorAction SilentlyContinue
	}
	Push-Location $repositoryRoot
	if ($CaptureRaw) {
		Assert-CaptureWorktreeClean
		$sourceShaBeforeCapture = Get-GitValue -Arguments @('rev-parse', 'HEAD')
		$null = Get-GitValue -Arguments @('merge-base', '--is-ancestor', $baseSha, $sourceShaBeforeCapture)
		New-Item -ItemType Directory -Path (Split-Path -Parent $captureStatePath) -Force | Out-Null
		[IO.File]::WriteAllText(
			$captureStatePath,
			"executionId=$executionId`n",
			(New-Object Text.UTF8Encoding($false)))
		if (Test-Path -LiteralPath $receiptArtifactPath) {
			Remove-Item -LiteralPath $receiptArtifactPath -Force
		}
		[IO.File]::WriteAllText(
			$receiptArtifactPath,
			"result=CAPTURE_IN_PROGRESS`n",
			(New-Object Text.UTF8Encoding($false)))
		$temporaryRawArtifactPath = "$rawArtifactPath.$PID.tmp"
		$temporaryReceiptPath = "$receiptArtifactPath.$PID.tmp"
		$captureArguments = @(
			'postgresTest',
			'--tests',
			'cloud.bamsongi.albammate.room.measurement.RoomLockStrategyComparisonPostgresTest.*',
			'--rerun',
			'--no-daemon',
			'--stacktrace')
		$captureOutput = @(& .\gradlew.bat @captureArguments 2>&1 | ForEach-Object { $_.ToString() })
		$captureExitCode = $LASTEXITCODE
		$captureLogPath = Join-Path $repositoryRoot 'build/room-lock-comparison-capture.log'
		[IO.File]::WriteAllLines(
			$captureLogPath,
			$captureOutput,
			(New-Object Text.UTF8Encoding($false)))
		if ($captureExitCode -ne 0) {
			exit $captureExitCode
		}
		Assert-CaptureWorktreeClean
		$sourceSha = Get-GitValue -Arguments @('rev-parse', 'HEAD')
		if ($sourceSha -ne $sourceShaBeforeCapture) {
			throw "Capture 실행 중 candidate HEAD가 변경되었습니다: before=$sourceShaBeforeCapture after=$sourceSha"
		}
		$testResultPath = Join-Path $repositoryRoot 'build/test-results/postgresTest/TEST-cloud.bamsongi.albammate.room.measurement.RoomLockStrategyComparisonPostgresTest.xml'
		$testResultXml = Get-Content -LiteralPath $testResultPath -Raw
		$rawLines = @([regex]::Matches(
			$testResultXml,
			'ROOM785_RAW candidate=C [^<\r\n]+') | ForEach-Object { $_.Value })
		if ($rawLines.Count -eq 0) {
			throw 'ROOM785_RAW line을 수집하지 못했습니다.'
		}
		$rawContent = ($rawLines -join "`n") + "`n"
		[IO.File]::WriteAllText($temporaryRawArtifactPath, $rawContent, (New-Object Text.UTF8Encoding($false)))
		$rawDigest = (Get-FileHash -LiteralPath $temporaryRawArtifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
		$testResult = [xml]$testResultXml
		$testCaseCount = [int]$testResult.testsuite.tests
		Write-ExecutionReceipt `
			-SourceSha $sourceSha `
			-RawDigest $rawDigest `
			-TestCaseCount $testCaseCount `
			-DestinationPath $temporaryReceiptPath
		[IO.File]::Move($temporaryRawArtifactPath, $rawArtifactPath, $true)
		[IO.File]::Move($temporaryReceiptPath, $receiptArtifactPath, $true)
		Remove-Item -LiteralPath $captureStatePath -Force
		Write-Output "ROOM785_CAPTURED_RAW path=$rawArtifactRelativePath lines=$($rawLines.Count) digest=$rawDigest"
		$receiptDigest = (Get-FileHash -LiteralPath $receiptArtifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
		Write-Output "ROOM785_CAPTURED_RECEIPT path=$receiptArtifactRelativePath testCases=$testCaseCount digest=$receiptDigest"
		exit 0
	}
	& .\gradlew.bat postgresTest --tests "cloud.bamsongi.albammate.room.measurement.RoomLockStrategyComparisonPostgresTest.*" --rerun --no-daemon --stacktrace
	if ($LASTEXITCODE -ne 0) {
		exit $LASTEXITCODE
	}
} finally {
	Pop-Location
	if ($temporaryRawArtifactPath -and (Test-Path -LiteralPath $temporaryRawArtifactPath)) {
		Remove-Item -LiteralPath $temporaryRawArtifactPath -Force
	}
	if ($temporaryReceiptPath -and (Test-Path -LiteralPath $temporaryReceiptPath)) {
		Remove-Item -LiteralPath $temporaryReceiptPath -Force
	}
	$env:ROOM785_ACTUAL_COMMAND = $previousCommand
	$env:ROOM785_RUNNER_PATH = $previousRunnerPath
	$env:ROOM785_EXECUTION_ID = $previousExecutionId
	$env:ROOM785_RAW_ARTIFACT_PATH = $previousRawArtifactPath
	if ($null -eq $previousCaptureRaw) {
		Remove-Item Env:ROOM785_CAPTURE_RAW -ErrorAction SilentlyContinue
	} else {
		$env:ROOM785_CAPTURE_RAW = $previousCaptureRaw
	}
}
