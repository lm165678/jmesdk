$ErrorActionPreference = 'Stop'
$sdkRoot = Split-Path -Parent $PSScriptRoot
$jdk = 'C:\Users\JohnKkk\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2'
$cluster = Join-Path $sdkRoot 'build/module-smoke-cluster'
$classes = Join-Path $cluster 'classes'
New-Item -ItemType Directory -Force $classes, "$cluster/modules", "$cluster/config/Modules" | Out-Null
$entries = @('build/cluster/modules/*', 'jme3-core-baselibs/release/modules/ext/*', 'jme3-core-libraries/release/modules/ext/*', 'netbeans/platform/lib/*', 'netbeans/platform/modules/*', 'netbeans/ide/modules/*', 'netbeans/java/modules/*')
$classpath = ($entries | ForEach-Object { Join-Path $sdkRoot $_ }) -join ';'
& "$jdk/bin/javac.exe" -encoding UTF-8 -proc:none -cp $classpath -d $classes "$PSScriptRoot/tests/SdkModuleSmoke.java"
if ($LASTEXITCODE -ne 0) { throw 'Module test compilation failed' }
$manifest = @"
Manifest-Version: 1.0
OpenIDE-Module: sdkverification
OpenIDE-Module-Specification-Version: 1.0
OpenIDE-Module-Install: sdkverification/SdkModuleSmoke.class
OpenIDE-Module-Module-Dependencies: com.jme3.gde.core/1, com.jme3.gde.core.baselibs/1, com.jme3.gde.core.libraries/1, com.jme3.gde.scenecomposer/1, org.openide.modules, org.openide.util, org.openide.util.lookup, org.openide.util.ui, org.openide.nodes, org.openide.windows, org.openide.awt, org.openide.loaders

"@
[IO.File]::WriteAllText("$cluster/manifest.mf", $manifest, [Text.Encoding]::ASCII)
& "$jdk/bin/jar.exe" cfm "$cluster/modules/sdkverification.jar" "$cluster/manifest.mf" -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Module test packaging failed' }
$xml = '<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE module PUBLIC "-//NetBeans//DTD Module Status 1.0//EN" "http://www.netbeans.org/dtds/module-status-1_0.dtd"><module name="sdkverification"><param name="autoload">false</param><param name="eager">false</param><param name="enabled">true</param><param name="jar">modules/sdkverification.jar</param><param name="reloadable">false</param></module>'
[IO.File]::WriteAllText("$cluster/config/Modules/sdkverification.xml", $xml, [Text.Encoding]::ASCII)
$profile = Join-Path $sdkRoot 'build/module-smoke-userdir'
New-Item -ItemType Directory -Force "$profile/config/Preferences/com/jme3/gde", "$sdkRoot/build/smoke-projects" | Out-Null
[IO.File]::WriteAllText("$profile/config/Preferences/com/jme3/gde/core.properties", "projects_path=M:/Sources/JMonkeyEngine3/jmesdk/build/smoke-projects`n", [Text.Encoding]::ASCII)
$clusters = @("$sdkRoot/build/cluster", $cluster) + @('extide','groovy','harness','ide','java','nb','platform').ForEach({"$sdkRoot/netbeans/$_"})
$extra = (Get-Content "$sdkRoot/nbproject/project.properties" | Where-Object {$_ -like 'run.args.extra=*'}).Substring(15).Replace('\=', '=').Split(' ', [StringSplitOptions]::RemoveEmptyEntries)
$launchArgs = @('--jdkhome', $jdk, '--userdir', $profile, '--branding', 'jmonkeyplatform', '--clusters', ($clusters -join ';'), '-J-Dnetbeans.logger.console=true', '-J--add-opens=java.base/java.nio=ALL-UNNAMED', '-J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED') + $extra
$process = Start-Process -FilePath "$sdkRoot/netbeans/platform/lib/nbexec64.exe" -ArgumentList $launchArgs -WorkingDirectory $sdkRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput "$sdkRoot/build/module-smoke-stdout.log" -RedirectStandardError "$sdkRoot/build/module-smoke-stderr.log"
$process.Id | Set-Content "$sdkRoot/build/module-smoke.pid"
Write-Output "Started test SDK PID=$($process.Id); isolated userdir=$profile"
