# P0 · Foto de las métricas internas del backend. Correr EN EL SERVIDOR (solo responde a localhost).
# Uso: .\scripts\medir-metricas.ps1 [-Puerto 8081] [-Salida medicion.json]
# Guarda un JSON con fecha para comparar antes/después.
param(
  [int]$Puerto = 8081,
  [string]$Salida = ("medicion-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".json")
)

$base = "http://localhost:$Puerto/actuator/metrics"
$metricas = @(
  @{ n = 'hikaricp.connections.active' },
  @{ n = 'hikaricp.connections.pending' },
  @{ n = 'hikaricp.connections.timeout' },
  @{ n = 'hikaricp.connections.max' },
  @{ n = 'executor.active'; t = 'name:ingesta-worker' },
  @{ n = 'executor.queued'; t = 'name:ingesta-worker' },
  @{ n = 'executor.active'; t = 'name:AzureOCR' },
  @{ n = 'executor.queued'; t = 'name:AzureOCR' },
  @{ n = 'executor.active'; t = 'name:lexia-async' },
  @{ n = 'executor.queued'; t = 'name:lexia-async' },
  @{ n = 'executor.active'; t = 'name:coactiva-anexo' },
  @{ n = 'lexia.worker.hilos.ocupados'; t = 'worker:ingesta' },
  @{ n = 'lexia.worker.hilos.ocupados'; t = 'worker:coactiva' },
  @{ n = 'lexia.jobs'; t = 'estado:PENDIENTE' },
  @{ n = 'lexia.jobs'; t = 'estado:EN_PROCESO' },
  @{ n = 'lexia.jobs'; t = 'estado:ERROR' },
  @{ n = 'lexia.jobs.espera.segundos'; t = 'tipo:PROCESAR_DOCUMENTO' },
  @{ n = 'lexia.jobs.espera.segundos'; t = 'tipo:PROCESAR_IA_EXPEDIENTE' },
  @{ n = 'lexia.jobs.duracion'; t = 'worker:ingesta' },
  @{ n = 'lexia.jobs.duracion'; t = 'worker:coactiva' },
  @{ n = 'lexia.memoria.borradores' },
  @{ n = 'lexia.memoria.borradores.bytes' },
  @{ n = 'lexia.memoria.sesiones.cache' },
  @{ n = 'lexia.sse.oyentes'; t = 'flujo:ingesta' },
  @{ n = 'lexia.sse.oyentes'; t = 'flujo:coactivas' },
  @{ n = 'jvm.memory.used'; t = 'area:heap' },
  @{ n = 'jvm.threads.live' },
  @{ n = 'http.server.requests' }
)

$filas = foreach ($m in $metricas) {
  $url = "$base/$($m.n)"
  if ($m.t) { $url += "?tag=$($m.t)" }
  try {
    $r = Invoke-RestMethod -Uri $url -TimeoutSec 10
    $valores = @{}
    foreach ($s in $r.measurements) { $valores[$s.statistic] = [math]::Round([double]$s.value, 3) }
    [pscustomobject]@{ metrica = $m.n; filtro = $m.t; valores = ($valores.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ' ' }
  } catch {
    [pscustomobject]@{ metrica = $m.n; filtro = $m.t; valores = "sin datos" }
  }
}

$filas | Format-Table -AutoSize
$filas | ConvertTo-Json -Depth 3 | Set-Content -Path $Salida -Encoding utf8
Write-Output "Guardado en $Salida"
