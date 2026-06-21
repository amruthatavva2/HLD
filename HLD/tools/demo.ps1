# End-to-end feature demo. Exercises every assignment feature and prints results.
# Usage:  pwsh ./tools/demo.ps1 [-Base http://localhost:8080]
param([string]$Base = "http://localhost:8080")

function Show($label, $obj) {
  Write-Host "`n### $label" -ForegroundColor Cyan
  $obj | ConvertTo-Json -Depth 6
}

Write-Host "================ 1. SUGGESTIONS (basic vs recency, edge cases) ================" -ForegroundColor Yellow
Show "GET /suggest?q=ip&ranking=basic" (Invoke-RestMethod "$Base/suggest?q=ip&ranking=basic")
Show "GET /suggest?q=IP (mixed case -> normalized)" (Invoke-RestMethod "$Base/suggest?q=IP&ranking=basic")
Show "GET /suggest?q= (empty -> [])" (Invoke-RestMethod "$Base/suggest?q=")
Show "GET /suggest?q=zzzzqwer (no match -> [])" (Invoke-RestMethod "$Base/suggest?q=zzzzqwer")

Write-Host "`n================ 2. DISTRIBUTED CACHE + CONSISTENT HASHING ================" -ForegroundColor Yellow
Invoke-RestMethod "$Base/admin/cache/clear" -Method Post | Out-Null
Show "GET /cache/debug?prefix=java&ranking=basic (cold -> MISS)" (Invoke-RestMethod "$Base/cache/debug?prefix=java&ranking=basic")
Invoke-RestMethod "$Base/suggest?q=java&ranking=basic" | Out-Null
Show "GET /cache/debug?prefix=java&ranking=basic (warm -> HIT)" (Invoke-RestMethod "$Base/cache/debug?prefix=java&ranking=basic")
Show "POST /admin/node?action=add&id=cache-node-3 (3->4: ~1/4 keys move)" (Invoke-RestMethod "$Base/admin/node?action=add&id=cache-node-3" -Method Post)
Invoke-RestMethod "$Base/admin/node?action=remove&id=cache-node-3" -Method Post | Out-Null

Write-Host "`n================ 3. TRENDING + RECENCY RANKING ================" -ForegroundColor Yellow
Invoke-RestMethod "$Base/admin/cache/clear" -Method Post | Out-Null
1..60 | ForEach-Object { Invoke-RestMethod "$Base/search" -Method Post -ContentType application/json -Body '{"query":"ipad mini deal"}' | Out-Null }
Write-Host "Submitted 'ipad mini deal' x60 (a rare query surging now)"
Show "RECENCY: surging query jumps up" (Invoke-RestMethod "$Base/suggest?q=ip&ranking=recency")
Show "BASIC:   surging query stays low/absent" (Invoke-RestMethod "$Base/suggest?q=ip&ranking=basic")
Show "GET /trending" (Invoke-RestMethod "$Base/trending?limit=5")

Write-Host "`n================ 4. BATCH WRITES ================" -ForegroundColor Yellow
$queries = @("alpha","bravo","charlie","delta","echo") | ForEach-Object { "$_ batch demo" }
1..1000 | ForEach-Object { Invoke-RestMethod "$Base/search" -Method Post -ContentType application/json -Body (@{query=($queries | Get-Random)} | ConvertTo-Json) | Out-Null }
Write-Host "Submitted 1000 searches across $($queries.Count) distinct queries"
Invoke-RestMethod "$Base/admin/flush" -Method Post | Out-Null
Show "GET /stats (see batchWrites.writeReductionRatio)" (Invoke-RestMethod "$Base/stats")
