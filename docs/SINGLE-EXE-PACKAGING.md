# 单 EXE 安装包

## 用户体验

发布者只需要提供：

```text
MinecraftCodexCompanion-Setup.exe
```

用户双击后不需要先解压 ZIP，也不需要管理员权限。安装器会：

1. 从自身读取压缩负载和逐文件 SHA-256 清单；
2. 拒绝绝对路径、`..`、NTFS ADS、Windows 设备名、重复路径和符号链接；
3. 解压到当前用户的固定应用目录：
   `%LOCALAPPDATA%\MinecraftCodexCompanion\Application\releases\<内容标识>`；
4. 对每个文件重新计算 SHA-256，只有全部一致才发布该版本；
5. 以同卷目录重命名发布完整版本，并原子替换 `current.json`；
6. 从已校验目录启动 `MinecraftCodexCompanion.exe`。

配置、API Key、桥接令牌和运行日志仍由应用在它原有的用户状态目录中生成，**不在安装包中**。安装器的 `current.json` 只记录包标识和相对版本目录，不记录本地绝对路径。

## 更新与异常恢复

- 版本目录由完整负载内容计算得出；相同负载会复用已校验版本。
- 新负载先完整写入随机 staging 目录，验证后再移动为正式版本；不会把半成品设为当前版本。
- 旧版本不会在安装时被删除。
- 如果现有版本被修改、损坏或多出未知文件，安装器不会删除、覆盖或继续运行该目录，而是创建隔离的 `repair` 版本并切换原子指针。
- 安装器只清理本次进程自己创建的 GUID staging 目录；不会扫描或删除用户的未知目录。

## 隐私门禁

`scripts/build-single-exe.ps1` 在嵌入前会再次验证 portable staging：

- 验证原有 `portable-manifest.json` 对所有负载文件的 SHA-256 覆盖；
- 拒绝 `.env`、Key、PEM、运行配置、桥接令牌、日志、世界、截图和 saves 目录；
- 拒绝 reparse point；
- 扫描非依赖文本，拒绝构建机用户目录、项目绝对路径、疑似 API Key 和 Bearer Token；
- 生成的资源清单只包含相对路径、大小和 SHA-256；
- 再扫描最终 EXE，确保没有嵌入构建机项目路径或用户目录。

构建和测试不调用外部 API、不读取用户现有配置，也不上传 EXE、哈希或扫描结果。

## 构建

从已经完成安全门禁的 portable staging 生成单 EXE：

```powershell
npm run build:single-exe
```

从源代码重建 portable staging，再生成单 EXE：

```powershell
npm run release:single-exe
```

产物位于：

```text
build\single-exe\MinecraftCodexCompanion-Setup.exe
build\single-exe\SHA256SUMS.txt
build\single-exe\single-exe-build.json
```

用户只需要下载第一个 EXE；另外两个文件用于发布者校验和审计。

当前构建机没有安装可用的 .NET SDK，只有 Windows 自带的 .NET Framework 4.8 编译器。因此脚本使用本机 `csc.exe` 生成无 sidecar 的 WinExe，并把压缩负载作为私有资源嵌入；运行时不下载框架或代码。目标 Windows 10/11 自带兼容的 .NET Framework。若以后发布面向精简 Windows 镜像的版本，应在有本地 SDK/runtime pack、且明确禁用在线 restore 的发布机上改为 .NET self-contained single-file publish。

未签名的本地开发构建虽然内容可校验，仍可能触发 SmartScreen。正式分发时应提供代码签名证书指纹：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-single-exe.ps1 `
  -BuildPortable `
  -RequireSignature `
  -SigningCertificateThumbprint $env:MC_COMPANION_SIGNING_CERT_SHA1
```

默认不使用时间戳服务器，避免构建过程产生外部网络请求。发布者明确传入 `-TimestampUrl` 时才会请求 RFC 3161 时间戳服务。证书、私钥和指纹不会写入负载或源代码。

## 离线测试

```powershell
npm run test:single-exe
```

测试只使用系统临时目录和一个无网络、无 Minecraft 的假启动程序，覆盖：

- 相同 staged payload 两次构建得到字节完全一致的 EXE；
- 嵌入压缩包及逐文件哈希自检；
- 解压、逐文件复核和真实启动安装后的假 EXE；
- 相同版本幂等复用；
- 新版本原子切换且旧版本保留；
- 已安装目录出现未知文件时不删除原目录，改用干净 repair 目录；
- 原子指针没有遗留临时文件。
