[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:9900",
    [int]$TimeoutSeconds = 30,
    [string]$OutputPath = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$wpBase = $BaseUrl.TrimEnd('/')
$wpSpecPath = Join-Path $PSScriptRoot "experiment-spec.json"

function Assert-WavePilotCondition {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "Reproduction check failed: $Message" }
}

function Assert-WavePilotNear {
    param([double]$Actual, [double]$Expected, [double]$Tolerance, [string]$Name)
    Assert-WavePilotCondition ([Math]::Abs($Actual - $Expected) -le $Tolerance) `
        "$Name expected $Expected but was $Actual"
}

function Wait-WavePilotExperiment {
    param([string]$JobId)
    $wpDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $wpJob = Invoke-RestMethod -Uri "$wpBase/api/experiments/$JobId" -TimeoutSec 10
        if ($wpJob.status -in @("SUCCEEDED", "FAILED", "CANCELLED")) { return $wpJob }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $wpDeadline)
    throw "Experiment timed out: $JobId"
}

function Wait-WavePilotArtifacts {
    param([string]$JobId)
    $wpDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        [array]$wpItems = Invoke-RestMethod -Uri "$wpBase/api/experiments/$JobId/artifacts" -TimeoutSec 10
        if ($wpItems.Count -eq 5 -and @($wpItems | Where-Object { -not $_.validated }).Count -eq 0) {
            return $wpItems
        }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $wpDeadline)
    throw "Validated artifacts were not ready: $JobId"
}

function Wait-WavePilotReplay {
    param([string]$ReplayId)
    $wpDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $wpRecord = Invoke-RestMethod -Uri "$wpBase/api/replays/$ReplayId" -TimeoutSec 10
        if ($wpRecord.status -in @("SUCCEEDED", "FAILED")) { return $wpRecord }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $wpDeadline)
    throw "Replay timed out: $ReplayId"
}

try {
    $null = Invoke-WebRequest -Uri "$wpBase/" -Method Get -TimeoutSec 5
} catch {
    throw "WavePilot is not reachable at $wpBase. Start the offline application first."
}

$wpSpecJson = [IO.File]::ReadAllText($wpSpecPath, [Text.Encoding]::UTF8)
$wpCreated = Invoke-RestMethod -Method Post -Uri "$wpBase/api/experiments" `
    -ContentType "application/json" -Body $wpSpecJson -TimeoutSec 10
$wpJob = Wait-WavePilotExperiment $wpCreated.jobId
Assert-WavePilotCondition ($wpJob.status -eq "SUCCEEDED") "experiment status was $($wpJob.status)"

[array]$wpArtifacts = Wait-WavePilotArtifacts $wpJob.jobId
$null = Invoke-RestMethod -Method Post -Uri "$wpBase/api/experiments/$($wpJob.jobId)/report" -TimeoutSec 10
$wpReportData = Invoke-RestMethod -Uri "$wpBase/api/experiments/$($wpJob.jobId)/report/data" -TimeoutSec 10

$wpReplayCreated = Invoke-RestMethod -Method Post -Uri "$wpBase/api/experiments/$($wpJob.jobId)/replay" `
    -ContentType "application/json" -Body '{"note":"GitHub reproducibility check"}' -TimeoutSec 10
$wpReplay = Wait-WavePilotReplay $wpReplayCreated.replayId
Assert-WavePilotCondition ($wpReplay.status -eq "SUCCEEDED") "replay status was $($wpReplay.status)"
$wpComparison = Invoke-RestMethod -Uri "$wpBase/api/replays/$($wpReplay.replayId)/comparison" -TimeoutSec 10
$wpAccuracyMetric = @($wpComparison.metrics | Where-Object { $_.metricName -eq "accuracy" })[0]

$wpEvaluation = Invoke-RestMethod -Method Post -Uri "$wpBase/api/evaluations/run" `
    -ContentType "application/json" -Body '{"datasetName":"default","modelName":"stub-v1"}' `
    -TimeoutSec $TimeoutSeconds
$wpPassedCases = @($wpEvaluation.results | Where-Object { $_.passed }).Count
$wpOverall = @($wpEvaluation.metrics | Where-Object { $_.metricName -eq "overallTaskCompletionRate" })[0]
$wpMetricsAtOne = @($wpEvaluation.metrics | Where-Object { $_.value -eq 1.0 }).Count

Assert-WavePilotCondition ($wpReportData.totalPoints -eq 6) "parameter point count was $($wpReportData.totalPoints)"
Assert-WavePilotCondition ($wpArtifacts.Count -eq 5) "artifact count was $($wpArtifacts.Count)"
Assert-WavePilotNear $wpReportData.accuracySummary.minAccuracy 0.901584 1e-12 "minAccuracy"
Assert-WavePilotNear $wpReportData.accuracySummary.maxAccuracy 0.95732 1e-12 "maxAccuracy"
Assert-WavePilotNear $wpReportData.accuracySummary.meanAccuracy 0.9283471666666667 1e-12 "meanAccuracy"
Assert-WavePilotCondition ($wpComparison.verdict -eq "REPRODUCIBLE") "replay verdict was $($wpComparison.verdict)"
Assert-WavePilotNear $wpAccuracyMetric.maxAbsDifference 0.0 1e-12 "replay accuracy maxAbsDifference"
Assert-WavePilotCondition ($wpPassedCases -eq 24 -and $wpEvaluation.results.Count -eq 24) `
    "evaluation passed $wpPassedCases/$($wpEvaluation.results.Count)"
Assert-WavePilotCondition ($wpMetricsAtOne -eq 11 -and $wpEvaluation.metrics.Count -eq 11) `
    "evaluation metrics at 1.0 were $wpMetricsAtOne/$($wpEvaluation.metrics.Count)"

$wpResult = [ordered]@{
    executionMode = "deterministic-offline-mock"
    jobId = $wpJob.jobId
    status = $wpJob.status
    parameterPoints = $wpReportData.totalPoints
    validatedArtifacts = $wpArtifacts.Count
    accuracy = [ordered]@{
        min = $wpReportData.accuracySummary.minAccuracy
        max = $wpReportData.accuracySummary.maxAccuracy
        mean = $wpReportData.accuracySummary.meanAccuracy
    }
    replay = [ordered]@{
        replayId = $wpReplay.replayId
        verdict = $wpComparison.verdict
        maxAbsDifference = $wpAccuracyMetric.maxAbsDifference
        tolerance = 1e-9
    }
    evaluation = [ordered]@{
        evaluationId = $wpEvaluation.evaluationId
        passedCases = $wpPassedCases
        totalCases = $wpEvaluation.results.Count
        metricsAtOne = $wpMetricsAtOne
        totalMetrics = $wpEvaluation.metrics.Count
        overallTaskCompletionRate = $wpOverall.value
    }
}

$wpJson = $wpResult | ConvertTo-Json -Depth 6
if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
    $wpResolvedOutput = [IO.Path]::GetFullPath($OutputPath)
    [IO.File]::WriteAllText($wpResolvedOutput, $wpJson + [Environment]::NewLine,
        [Text.UTF8Encoding]::new($false))
}
$wpJson
