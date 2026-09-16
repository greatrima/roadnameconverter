param(
    [string]$OutputPath = (Join-Path $PSScriptRoot "..\app\src\main\assets\address_dictionary.tsv.gz"),
    [switch]$ReuseExistingRoadData
)

$ErrorActionPreference = "Stop"
$baseUrl = "https://www.juso.go.kr"
$sourceUrl = "$baseUrl/api/ahu/selectRoadList"
$records = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)

function Invoke-JsonPost([string]$Uri, [hashtable]$Body) {
    $json = $Body | ConvertTo-Json -Depth 5 -Compress
    for ($attempt = 1; $attempt -le 4; $attempt++) {
        try {
            return Invoke-RestMethod -Uri $Uri -Method Post -ContentType "application/json;charset=UTF-8" `
                -Body $json -TimeoutSec 90
        } catch {
            if ($attempt -eq 4) {
                Write-Warning "request failed: $Uri body=$json"
                throw
            }
            Start-Sleep -Seconds (2 * $attempt)
        }
    }
}

if ($ReuseExistingRoadData -and (Test-Path -LiteralPath $OutputPath)) {
    $existingFile = [System.IO.File]::OpenRead($OutputPath)
    try {
        $existingGzip = [System.IO.Compression.GZipStream]::new(
            $existingFile,
            [System.IO.Compression.CompressionMode]::Decompress
        )
        try {
            $existingReader = [System.IO.StreamReader]::new($existingGzip, [System.Text.Encoding]::UTF8)
            try {
                while (($line = $existingReader.ReadLine()) -ne $null) {
                    if ($line -and -not $line.StartsWith("#")) { [void]$records.Add($line) }
                }
            } finally { $existingReader.Dispose() }
        } finally { $existingGzip.Dispose() }
    } finally { $existingFile.Dispose() }
    Write-Host "기존 도로명 관계를 재사용합니다."
} else {
  $provinces = Invoke-JsonPost "$baseUrl/api/jusoCommon/selectSidoList" @{}
  foreach ($province in $provinces) {
    if ($province.ctpvCd -eq "361102") {
        $districts = @([pscustomobject]@{
            sggNm = $province.ctpvNm
            lgvReplcCd = "361102"
        })
    } else {
        $districts = Invoke-JsonPost "$baseUrl/api/jusoCommon/selectSggList" @{
            ctpvCd = $province.ctpvCd
        }
    }
    foreach ($district in $districts) {
        $districtName = ([string]$district.sggNm).Trim()
        if (-not $districtName) { continue }
        [void]$records.Add("A`t$($province.ctpvNm)`t$districtName`t")
        $page = 0
        $totalPages = 1
        do {
            $request = @{
                roadNm = ""
                ctpvCd = $province.ctpvCd
                lgvReplcCd = $district.lgvReplcCd
                emdCd = ""
                useYn = "Y"
                expansionCheck = $true
                lgvCdRoadNmCd = ""
                roadNmCount = ""
                pageable = @{ page = $page; size = 10000 }
            }
            $response = Invoke-JsonPost $sourceUrl $request
            $roadPage = $response.results.roadList
            $totalPages = [int]$roadPage.totalPages

            foreach ($item in $roadPage.content) {
                $emd = ([string]$item.emdNm).Trim()
                $road = ([string]$item.roadNm).Trim()
                [void]$records.Add("A`t$($province.ctpvNm)`t$districtName`t$emd")
                if ($road) {
                    [void]$records.Add("R`t$($province.ctpvNm)`t$districtName`t$emd`t$road")
                }
            }
            Write-Host ("{0} {1}: {2}/{3}" -f $province.ctpvNm, $districtName, ($page + 1), $totalPages)
            $page++
        } while ($page -lt $totalPages)
    }
  }
}

# The road-name feed stops at 읍/면/동. Merge the current legal-district feed so 법정리 is
# available for parcel correction and optional region filters as well.
$districtsByProvince = @{}
foreach ($record in $records) {
    $fields = $record.Split("`t")
    if ($fields.Count -lt 3 -or -not $fields[1] -or -not $fields[2]) { continue }
    if (-not $districtsByProvince.ContainsKey($fields[1])) {
        $districtsByProvince[$fields[1]] = [System.Collections.Generic.HashSet[string]]::new()
    }
    [void]$districtsByProvince[$fields[1]].Add($fields[2])
}

$legalZip = Join-Path ([System.IO.Path]::GetTempPath()) ("address-lens-legal-" + [guid]::NewGuid() + ".zip")
$legalDir = Join-Path ([System.IO.Path]::GetTempPath()) ("address-lens-legal-" + [guid]::NewGuid())
try {
    Invoke-WebRequest -UseBasicParsing -Uri "https://www.code.go.kr/etc/codeFullDown.do" `
        -Method Post -Body @{ codeseId = "법정동코드" } -OutFile $legalZip -TimeoutSec 90
    Expand-Archive -LiteralPath $legalZip -DestinationPath $legalDir
    $legalFile = Get-ChildItem -LiteralPath $legalDir -File | Select-Object -First 1
    $reader = [System.IO.StreamReader]::new($legalFile.FullName, [System.Text.Encoding]::GetEncoding(949))
    try {
        [void]$reader.ReadLine()
        while (($line = $reader.ReadLine()) -ne $null) {
            $fields = $line.Split("`t")
            if ($fields.Count -lt 3 -or $fields[2].Trim() -ne "존재") { continue }
            $fullName = $fields[1].Trim()
            $province = $districtsByProvince.Keys |
                Where-Object { $fullName -eq $_ -or $fullName.StartsWith("$_ ") } |
                Sort-Object Length -Descending | Select-Object -First 1
            if (-not $province) { continue }
            $remainder = $fullName.Substring($province.Length).Trim()
            $district = $districtsByProvince[$province] |
                Where-Object { $remainder -eq $_ -or $remainder.StartsWith("$_ ") } |
                Sort-Object Length -Descending | Select-Object -First 1
            if (-not $district) { continue }
            $localPath = $remainder.Substring($district.Length).Trim()
            foreach ($locality in ($localPath -split "\\s+")) {
                if ($locality -match "(읍|면|동|리|가)$") {
                    [void]$records.Add("A`t$province`t$district`t$locality")
                }
            }
        }
    } finally { $reader.Dispose() }
} finally {
    Remove-Item -LiteralPath $legalZip -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $legalDir -Recurse -ErrorAction SilentlyContinue
}

$directory = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$temp = [System.IO.Path]::GetTempFileName()
try {
    $utf8 = [System.Text.UTF8Encoding]::new($false)
    $file = [System.IO.File]::Create($OutputPath)
    try {
        $gzip = [System.IO.Compression.GZipStream]::new(
            $file,
            [System.IO.Compression.CompressionLevel]::Optimal
        )
        try {
            $writer = [System.IO.StreamWriter]::new($gzip, $utf8)
            try {
                $writer.WriteLine("# address-lens-dictionary-v1")
                $writer.WriteLine("# generated={0}" -f (Get-Date -Format "yyyy-MM-dd"))
                $writer.WriteLine("# source=https://www.juso.go.kr/api/ahu/selectRoadList")
                foreach ($record in ($records | Sort-Object)) {
                    $writer.WriteLine($record)
                }
            } finally { $writer.Dispose() }
        } finally { $gzip.Dispose() }
    } finally { $file.Dispose() }
} finally {
    Remove-Item -LiteralPath $temp -ErrorAction SilentlyContinue
}

$info = Get-Item -LiteralPath $OutputPath
Write-Host ("records={0} gzipBytes={1} output={2}" -f $records.Count, $info.Length, $info.FullName)
