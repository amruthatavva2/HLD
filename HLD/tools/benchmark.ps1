# Latency benchmark for GET /suggest.
# Fires N requests with a mix of repeated prefixes (so the cache warms up), then reads
# /stats for server-side p50/p95/p99 and the cache hit rate.
#
# Usage:  pwsh ./tools/benchmark.ps1 [-Count 3000] [-Base http://localhost:8080]
param(
  [int]$Count = 3000,
  [string]$Base = "http://localhost:8080"
)

$prefixes = @("a","ip","iph","ipa","ipad","ja","jav","java","py","pyt","best","sam","nik",
              "net","you","wea","new","how","air","bes","goo","cha","lap","gam","mou")

Write-Host "Warming up..." -ForegroundColor Cyan
foreach ($p in $prefixes) { Invoke-RestMethod "$Base/suggest?q=$p&ranking=basic" | Out-Null }

Write-Host "Firing $Count /suggest requests..." -ForegroundColor Cyan
$sw = [System.Diagnostics.Stopwatch]::StartNew()
for ($i = 0; $i -lt $Count; $i++) {
  $p = $prefixes[(Get-Random -Maximum $prefixes.Length)]
  $mode = if ($i % 2 -eq 0) { "basic" } else { "recency" }
  Invoke-RestMethod "$Base/suggest?q=$p&ranking=$mode" | Out-Null
}
$sw.Stop()

$throughput = [math]::Round($Count / $sw.Elapsed.TotalSeconds, 0)
Write-Host ("Client wall time: {0:N1}s  (~{1} req/s incl. HTTP overhead)" -f $sw.Elapsed.TotalSeconds, $throughput) -ForegroundColor Green

$stats = Invoke-RestMethod "$Base/stats"
Write-Host "`n=== Server-side /suggest latency (microseconds) ===" -ForegroundColor Yellow
$stats.suggestLatency | ConvertTo-Json
Write-Host "=== Cache ===" -ForegroundColor Yellow
$stats.cache | ConvertTo-Json
