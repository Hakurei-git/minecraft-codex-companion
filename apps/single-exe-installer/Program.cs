using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Resources;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;

namespace MinecraftCodexCompanion.SingleExeInstaller
{
    internal static class Program
    {
        private const string ProductDirectoryName = "MinecraftCodexCompanion";
        private const string ApplicationDirectoryName = "Application";
        private const string ReleasesDirectoryName = "releases";
        private const string CurrentFileName = "current.json";
        private const string ReleaseMarkerName = ".minecraft-codex-release.json";
        private const string TargetExecutableName = "MinecraftCodexCompanion.exe";
        private const int ReleaseIdLength = 24;
        private const string PayloadIndexResource = "MinecraftCodexCompanion.SingleExe.PayloadIndex";
        private const string PayloadArchiveResource = "MinecraftCodexCompanion.SingleExe.PayloadArchive";
        private const string InstallerMutexName = "Local\\MinecraftCodexCompanion.SingleExeInstaller";

        private static readonly UTF8Encoding Utf8NoBom = new UTF8Encoding(false, true);

        [Flags]
        private enum MoveFileFlags
        {
            ReplaceExisting = 0x1,
            WriteThrough = 0x8
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool MoveFileEx(string existingFileName, string newFileName, MoveFileFlags flags);

        [STAThread]
        private static int Main(string[] args)
        {
            // WinForms requires this process-wide setting before any Form or
            // MessageBox (including parse-error handling) creates an
            // IWin32Window instance.
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            Options options;
            try
            {
                options = Options.Parse(args);
            }
            catch (Exception error)
            {
                ShowError(false, error);
                return 2;
            }

            try
            {
                if (options.SelfTest)
                {
                    RunSelfTest(options);
                    return 0;
                }

                if (options.Quiet)
                {
                    InstallAndMaybeLaunch(options);
                    return 0;
                }

                return RunWithProgress(options);
            }
            catch (Exception error)
            {
                TryWriteFailureResult(options, error);
                ShowError(options.Quiet, error);
                return 1;
            }
        }

        private static int RunWithProgress(Options options)
        {
            Exception failure = null;
            using (ProgressWindow window = new ProgressWindow())
            {
                window.Shown += delegate
                {
                    Thread worker = new Thread(delegate()
                    {
                        try
                        {
                            InstallAndMaybeLaunch(options);
                        }
                        catch (Exception error)
                        {
                            failure = error;
                        }
                        finally
                        {
                            try
                            {
                                window.BeginInvoke(new Action(window.Close));
                            }
                            catch (InvalidOperationException)
                            {
                            }
                        }
                    });
                    worker.IsBackground = true;
                    worker.Name = "Minecraft Codex Companion installer";
                    worker.Start();
                };

                Application.Run(window);
            }

            if (failure != null)
            {
                ShowError(false, failure);
                return 1;
            }
            return 0;
        }

        private static void RunSelfTest(Options options)
        {
            LoadedManifest loaded = LoadManifest();
            ValidateArchive(loaded.Manifest, true);

            // Exercise the path guard with representative aliases and traversal attempts.
            string ignored;
            if (TryNormalizeRelativePath("../escape", out ignored)
                || TryNormalizeRelativePath("C:/escape", out ignored)
                || TryNormalizeRelativePath("safe/../../escape", out ignored)
                || TryNormalizeRelativePath("safe:stream", out ignored)
                || !TryNormalizeRelativePath("runtime/node.exe", out ignored))
            {
                throw new InvalidDataException("The embedded path-safety self-test failed.");
            }

            WriteOptionalResult(options, loaded.Manifest, null, "self-test");
        }

        private static void InstallAndMaybeLaunch(Options options)
        {
            using (Mutex mutex = new Mutex(false, InstallerMutexName))
            {
                bool ownsMutex = false;
                try
                {
                    try
                    {
                        ownsMutex = mutex.WaitOne(TimeSpan.FromMinutes(2));
                    }
                    catch (AbandonedMutexException)
                    {
                        ownsMutex = true;
                    }
                    if (!ownsMutex)
                    {
                        throw new IOException("另一个安装进程仍在运行，请稍后重试。");
                    }

                    LoadedManifest loaded = LoadManifest();
                    string applicationRoot = ResolveApplicationRoot(options);
                    EnsureApplicationRoot(applicationRoot);

                    string releasesRoot = Path.Combine(applicationRoot, ReleasesDirectoryName);
                    EnsurePlainDirectory(releasesRoot, true);
                    EnsureFreeSpace(releasesRoot, loaded.Manifest.TotalBytes);

                    string releaseName;
                    string releaseRoot;
                    if (!TryFindReusableRelease(applicationRoot, releasesRoot, loaded, out releaseName, out releaseRoot))
                    {
                        releaseName = SelectNewReleaseName(releasesRoot, loaded.Manifest.PackageId);
                        releaseRoot = InstallRelease(releasesRoot, releaseName, loaded);
                    }

                    VerifyInstalledRelease(releaseRoot, loaded);
                    WriteCurrentPointer(applicationRoot, loaded.Manifest.PackageId, releaseName);
                    WriteOptionalResult(options, loaded.Manifest, releaseName, "installed");

                    if (!options.InstallOnly)
                    {
                        string launcher = SafeCombine(releaseRoot, loaded.Manifest.TargetExecutable);
                        VerifyOneFile(launcher, loaded.Manifest.FileByPath[loaded.Manifest.TargetExecutable]);
                        ProcessStartInfo start = new ProcessStartInfo();
                        start.FileName = launcher;
                        start.WorkingDirectory = releaseRoot;
                        start.UseShellExecute = true;
                        Process.Start(start);
                    }
                }
                finally
                {
                    if (ownsMutex)
                    {
                        mutex.ReleaseMutex();
                    }
                }
            }
        }

        private static string ResolveApplicationRoot(Options options)
        {
            if (!String.IsNullOrEmpty(options.TestRoot))
            {
                string fullTestRoot = Path.GetFullPath(options.TestRoot);
                string tempRoot = EnsureTrailingSeparator(Path.GetFullPath(Path.GetTempPath()));
                if (!EnsureTrailingSeparator(fullTestRoot).StartsWith(tempRoot, StringComparison.OrdinalIgnoreCase))
                {
                    throw new ArgumentException("--test-root 必须位于系统临时目录内。");
                }
                return fullTestRoot.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            }

            string localApplicationData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            if (String.IsNullOrWhiteSpace(localApplicationData))
            {
                throw new DirectoryNotFoundException("无法确定当前用户的 LocalAppData 目录。");
            }
            return Path.Combine(localApplicationData, ProductDirectoryName, ApplicationDirectoryName);
        }

        private static void EnsureApplicationRoot(string applicationRoot)
        {
            string fullRoot = Path.GetFullPath(applicationRoot);
            string parent = Path.GetDirectoryName(fullRoot);
            if (String.IsNullOrEmpty(parent))
            {
                throw new InvalidDataException("安装目录没有安全的父目录。");
            }
            EnsurePlainDirectory(parent, true);
            EnsurePlainDirectory(fullRoot, true);
        }

        private static void EnsurePlainDirectory(string path, bool create)
        {
            if (create)
            {
                Directory.CreateDirectory(path);
            }
            DirectoryInfo info = new DirectoryInfo(path);
            if (!info.Exists)
            {
                throw new DirectoryNotFoundException("目录不存在：" + path);
            }
            if ((info.Attributes & FileAttributes.ReparsePoint) != 0)
            {
                throw new IOException("安装过程拒绝跟随重解析点：" + path);
            }
        }

        private static void EnsureFreeSpace(string releasesRoot, long totalBytes)
        {
            string root = Path.GetPathRoot(Path.GetFullPath(releasesRoot));
            if (String.IsNullOrEmpty(root))
            {
                return;
            }
            DriveInfo drive = new DriveInfo(root);
            long reserve = 64L * 1024L * 1024L;
            if (totalBytes > Int64.MaxValue - reserve || drive.AvailableFreeSpace < totalBytes + reserve)
            {
                throw new IOException("可用磁盘空间不足，无法安全完成原子安装。");
            }
        }

        private static bool TryFindReusableRelease(
            string applicationRoot,
            string releasesRoot,
            LoadedManifest loaded,
            out string releaseName,
            out string releaseRoot)
        {
            releaseName = null;
            releaseRoot = null;

            string currentName;
            if (TryReadCurrentPointer(applicationRoot, loaded.Manifest.PackageId, out currentName))
            {
                string currentPath = Path.Combine(releasesRoot, currentName);
                if (TryVerifyInstalledRelease(currentPath, loaded))
                {
                    releaseName = currentName;
                    releaseRoot = currentPath;
                    return true;
                }
            }

            string canonicalName = loaded.Manifest.PackageId.Substring(0, ReleaseIdLength);
            string canonicalPath = Path.Combine(releasesRoot, canonicalName);
            if (TryVerifyInstalledRelease(canonicalPath, loaded))
            {
                releaseName = canonicalName;
                releaseRoot = canonicalPath;
                return true;
            }
            return false;
        }

        private static bool TryReadCurrentPointer(string applicationRoot, string expectedPackageId, out string releaseName)
        {
            releaseName = null;
            string pointerPath = Path.Combine(applicationRoot, CurrentFileName);
            if (!File.Exists(pointerPath))
            {
                return false;
            }
            FileInfo pointerInfo = new FileInfo(pointerPath);
            if ((pointerInfo.Attributes & FileAttributes.ReparsePoint) != 0 || pointerInfo.Length > 65536)
            {
                return false;
            }
            try
            {
                JavaScriptSerializer serializer = NewSerializer();
                InstallPointer pointer = serializer.Deserialize<InstallPointer>(File.ReadAllText(pointerPath, Utf8NoBom));
                if (pointer == null
                    || pointer.format != 1
                    || !String.Equals(pointer.packageId, expectedPackageId, StringComparison.Ordinal)
                    || !IsSafeReleaseName(pointer.releaseName))
                {
                    return false;
                }
                releaseName = pointer.releaseName;
                return true;
            }
            catch
            {
                return false;
            }
        }

        private static bool TryVerifyInstalledRelease(string releaseRoot, LoadedManifest loaded)
        {
            try
            {
                if (!Directory.Exists(releaseRoot))
                {
                    return false;
                }
                VerifyInstalledRelease(releaseRoot, loaded);
                return true;
            }
            catch
            {
                // A damaged or user-modified directory is retained unchanged. A new
                // isolated repair release will be installed instead.
                return false;
            }
        }

        private static string SelectNewReleaseName(string releasesRoot, string packageId)
        {
            string shortId = packageId.Substring(0, ReleaseIdLength);
            string canonical = Path.Combine(releasesRoot, shortId);
            if (!Directory.Exists(canonical) && !File.Exists(canonical))
            {
                return shortId;
            }
            return shortId + "-repair-" + Guid.NewGuid().ToString("N").Substring(0, 12);
        }

        private static string InstallRelease(string releasesRoot, string releaseName, LoadedManifest loaded)
        {
            if (!IsSafeReleaseName(releaseName))
            {
                throw new InvalidDataException("生成了不安全的发布目录名称。");
            }

            string stagingName = ".staging-" + Guid.NewGuid().ToString("N");
            string stagingRoot = Path.Combine(releasesRoot, stagingName);
            string releaseRoot = Path.Combine(releasesRoot, releaseName);
            bool stagingCreated = false;
            try
            {
                Directory.CreateDirectory(stagingRoot);
                stagingCreated = true;
                EnsurePlainDirectory(stagingRoot, false);
                ExtractPayload(stagingRoot, loaded);
                VerifyInstalledRelease(stagingRoot, loaded);

                if (Directory.Exists(releaseRoot) || File.Exists(releaseRoot))
                {
                    throw new IOException("目标发布目录在安装期间已被占用；未覆盖任何现有文件。");
                }
                Directory.Move(stagingRoot, releaseRoot);
                stagingCreated = false;
                return releaseRoot;
            }
            finally
            {
                if (stagingCreated && Directory.Exists(stagingRoot))
                {
                    // This GUID-named directory was created by this process only.
                    Directory.Delete(stagingRoot, true);
                }
            }
        }

        private static void ExtractPayload(string stagingRoot, LoadedManifest loaded)
        {
            using (Stream resource = OpenResource(PayloadArchiveResource))
            using (ZipArchive archive = new ZipArchive(resource, ZipArchiveMode.Read, false))
            {
                Dictionary<string, ZipArchiveEntry> entries = GetArchiveEntries(archive, loaded.Manifest);
                byte[] buffer = new byte[128 * 1024];
                foreach (PayloadFile entry in loaded.Manifest.Files)
                {
                    ZipArchiveEntry zipEntry = entries[entry.Path];
                    string destination = SafeCombine(stagingRoot, entry.Path);
                    string parent = Path.GetDirectoryName(destination);
                    EnsureSubdirectories(stagingRoot, parent);

                    long written = 0;
                    byte[] digest;
                    using (Stream source = zipEntry.Open())
                    using (FileStream output = new FileStream(
                        destination,
                        FileMode.CreateNew,
                        FileAccess.Write,
                        FileShare.None,
                        buffer.Length,
                        FileOptions.SequentialScan | FileOptions.WriteThrough))
                    using (SHA256 sha = SHA256.Create())
                    {
                        int read;
                        while ((read = source.Read(buffer, 0, buffer.Length)) > 0)
                        {
                            output.Write(buffer, 0, read);
                            sha.TransformBlock(buffer, 0, read, buffer, 0);
                            written += read;
                            if (written > entry.Size)
                            {
                                throw new InvalidDataException("文件长度超过清单：" + entry.Path);
                            }
                        }
                        sha.TransformFinalBlock(new byte[0], 0, 0);
                        output.Flush(true);
                        digest = sha.Hash;
                    }

                    if (written != entry.Size || !FixedTimeEquals(ToHex(digest), entry.Sha256))
                    {
                        throw new InvalidDataException("文件 SHA-256 校验失败：" + entry.Path);
                    }
                }
            }

            string marker = Path.Combine(stagingRoot, ReleaseMarkerName);
            using (FileStream stream = new FileStream(marker, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                stream.Write(loaded.RawJson, 0, loaded.RawJson.Length);
                stream.Flush(true);
            }
        }

        private static void EnsureSubdirectories(string root, string destinationParent)
        {
            string fullRoot = EnsureTrailingSeparator(Path.GetFullPath(root));
            string fullParent = Path.GetFullPath(destinationParent);
            if (!EnsureTrailingSeparator(fullParent).StartsWith(fullRoot, StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidDataException("解压路径逃逸了发布目录。");
            }
            Directory.CreateDirectory(fullParent);

            string rootWithoutSeparator = fullRoot.TrimEnd(Path.DirectorySeparatorChar);
            string relative = String.Equals(fullParent, rootWithoutSeparator, StringComparison.OrdinalIgnoreCase)
                ? String.Empty
                : fullParent.Substring(fullRoot.Length).TrimEnd(Path.DirectorySeparatorChar);
            string current = rootWithoutSeparator;
            if (!String.IsNullOrEmpty(relative))
            {
                string[] parts = relative.Split(Path.DirectorySeparatorChar);
                for (int index = 0; index < parts.Length; index++)
                {
                    current = Path.Combine(current, parts[index]);
                    EnsurePlainDirectory(current, false);
                }
            }
        }

        private static void VerifyInstalledRelease(string releaseRoot, LoadedManifest loaded)
        {
            EnsurePlainDirectory(releaseRoot, false);
            HashSet<string> allowed = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (PayloadFile file in loaded.Manifest.Files)
            {
                allowed.Add(file.Path);
                VerifyOneFile(SafeCombine(releaseRoot, file.Path), file);
            }
            allowed.Add(ReleaseMarkerName);

            List<string> actual = EnumeratePlainFiles(releaseRoot);
            foreach (string relative in actual)
            {
                if (!allowed.Contains(relative))
                {
                    throw new InvalidDataException("发布目录包含未受清单保护的文件：" + relative);
                }
            }
            if (actual.Count != allowed.Count)
            {
                throw new InvalidDataException("发布目录文件数量与清单不一致。");
            }

            byte[] markerBytes = File.ReadAllBytes(Path.Combine(releaseRoot, ReleaseMarkerName));
            if (!FixedTimeEquals(markerBytes, loaded.RawJson))
            {
                throw new InvalidDataException("发布目录标记与嵌入清单不一致。");
            }
        }

        private static List<string> EnumeratePlainFiles(string releaseRoot)
        {
            string fullRoot = EnsureTrailingSeparator(Path.GetFullPath(releaseRoot));
            List<string> files = new List<string>();
            Stack<DirectoryInfo> pending = new Stack<DirectoryInfo>();
            pending.Push(new DirectoryInfo(releaseRoot));
            while (pending.Count > 0)
            {
                DirectoryInfo directory = pending.Pop();
                if ((directory.Attributes & FileAttributes.ReparsePoint) != 0)
                {
                    throw new IOException("发布目录包含重解析点：" + directory.FullName);
                }
                foreach (DirectoryInfo child in directory.GetDirectories())
                {
                    if ((child.Attributes & FileAttributes.ReparsePoint) != 0)
                    {
                        throw new IOException("发布目录包含重解析点：" + child.FullName);
                    }
                    pending.Push(child);
                }
                foreach (FileInfo file in directory.GetFiles())
                {
                    if ((file.Attributes & FileAttributes.ReparsePoint) != 0)
                    {
                        throw new IOException("发布目录包含重解析点：" + file.FullName);
                    }
                    string relative = file.FullName.Substring(fullRoot.Length).Replace('\\', '/');
                    files.Add(relative);
                }
            }
            return files;
        }

        private static void VerifyOneFile(string path, PayloadFile expected)
        {
            FileInfo info = new FileInfo(path);
            if (!info.Exists || (info.Attributes & FileAttributes.ReparsePoint) != 0 || info.Length != expected.Size)
            {
                throw new InvalidDataException("发布文件缺失或长度不符：" + expected.Path);
            }
            using (FileStream stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read))
            using (SHA256 sha = SHA256.Create())
            {
                string actual = ToHex(sha.ComputeHash(stream));
                if (!FixedTimeEquals(actual, expected.Sha256))
                {
                    throw new InvalidDataException("发布文件 SHA-256 校验失败：" + expected.Path);
                }
            }
        }

        private static void WriteCurrentPointer(string applicationRoot, string packageId, string releaseName)
        {
            InstallPointer pointer = new InstallPointer();
            pointer.format = 1;
            pointer.packageId = packageId;
            pointer.releaseName = releaseName;
            byte[] bytes = Utf8NoBom.GetBytes(NewSerializer().Serialize(pointer));
            AtomicWriteKnownFile(Path.Combine(applicationRoot, CurrentFileName), bytes);
        }

        private static void AtomicWriteKnownFile(string destination, byte[] bytes)
        {
            string parent = Path.GetDirectoryName(destination);
            EnsurePlainDirectory(parent, false);
            if (Directory.Exists(destination))
            {
                throw new IOException("预期文件路径被目录占用：" + destination);
            }
            if (File.Exists(destination))
            {
                FileInfo existing = new FileInfo(destination);
                if ((existing.Attributes & FileAttributes.ReparsePoint) != 0)
                {
                    throw new IOException("拒绝覆盖重解析点：" + destination);
                }
            }

            string temporary = Path.Combine(parent, ".current-" + Guid.NewGuid().ToString("N") + ".tmp");
            try
            {
                using (FileStream stream = new FileStream(
                    temporary,
                    FileMode.CreateNew,
                    FileAccess.Write,
                    FileShare.None,
                    4096,
                    FileOptions.WriteThrough))
                {
                    stream.Write(bytes, 0, bytes.Length);
                    stream.Flush(true);
                }
                if (!MoveFileEx(temporary, destination, MoveFileFlags.ReplaceExisting | MoveFileFlags.WriteThrough))
                {
                    throw new IOException("无法原子更新安装指针。Win32 错误：" + Marshal.GetLastWin32Error());
                }
            }
            finally
            {
                if (File.Exists(temporary))
                {
                    File.Delete(temporary);
                }
            }
        }

        private static void WriteOptionalResult(Options options, PayloadManifest manifest, string releaseName, string status)
        {
            if (String.IsNullOrEmpty(options.ResultFile))
            {
                return;
            }
            string resultPath = Path.GetFullPath(options.ResultFile);
            string tempRoot = EnsureTrailingSeparator(Path.GetFullPath(Path.GetTempPath()));
            if (!EnsureTrailingSeparator(resultPath).StartsWith(tempRoot, StringComparison.OrdinalIgnoreCase))
            {
                throw new ArgumentException("--result-file 必须位于系统临时目录内。");
            }
            string parent = Path.GetDirectoryName(resultPath);
            EnsurePlainDirectory(parent, true);
            InstallResult result = new InstallResult();
            result.format = 1;
            result.status = status;
            result.packageId = manifest.PackageId;
            result.releaseName = releaseName;
            byte[] bytes = Utf8NoBom.GetBytes(NewSerializer().Serialize(result));
            AtomicWriteKnownFile(resultPath, bytes);
        }

        private static void TryWriteFailureResult(Options options, Exception error)
        {
            if (options == null || String.IsNullOrEmpty(options.ResultFile))
            {
                return;
            }
            try
            {
                string resultPath = Path.GetFullPath(options.ResultFile);
                string tempRoot = EnsureTrailingSeparator(Path.GetFullPath(Path.GetTempPath()));
                if (!EnsureTrailingSeparator(resultPath).StartsWith(tempRoot, StringComparison.OrdinalIgnoreCase))
                {
                    return;
                }
                string parent = Path.GetDirectoryName(resultPath);
                EnsurePlainDirectory(parent, true);
                FailureResult result = new FailureResult();
                result.format = 1;
                result.status = "error";
                result.message = error.Message;
                byte[] bytes = Utf8NoBom.GetBytes(NewSerializer().Serialize(result));
                AtomicWriteKnownFile(resultPath, bytes);
            }
            catch
            {
            }
        }

        private static LoadedManifest LoadManifest()
        {
            byte[] raw;
            using (Stream stream = OpenResource(PayloadIndexResource))
            using (MemoryStream memory = new MemoryStream())
            {
                stream.CopyTo(memory);
                raw = memory.ToArray();
            }
            if (raw.Length == 0 || raw.Length > 32 * 1024 * 1024)
            {
                throw new InvalidDataException("嵌入清单大小无效。");
            }

            JavaScriptSerializer serializer = NewSerializer();
            PayloadIndexDto dto = serializer.Deserialize<PayloadIndexDto>(Utf8NoBom.GetString(raw));
            if (dto == null || dto.format != 1 || dto.files == null || dto.files.Length == 0)
            {
                throw new InvalidDataException("嵌入清单格式无效。");
            }
            if (!IsSha256(dto.packageId))
            {
                throw new InvalidDataException("嵌入清单缺少有效的包标识。");
            }

            string target;
            if (!TryNormalizeRelativePath(dto.targetExecutable, out target)
                || !String.Equals(target, TargetExecutableName, StringComparison.Ordinal))
            {
                throw new InvalidDataException("嵌入清单指定了无效的启动程序。");
            }

            List<PayloadFile> files = new List<PayloadFile>();
            Dictionary<string, PayloadFile> byPath = new Dictionary<string, PayloadFile>(StringComparer.OrdinalIgnoreCase);
            long totalBytes = 0;
            foreach (PayloadFileDto fileDto in dto.files)
            {
                if (fileDto == null || fileDto.size < 0 || !IsSha256(fileDto.sha256))
                {
                    throw new InvalidDataException("嵌入清单包含无效文件记录。");
                }
                string path;
                if (!TryNormalizeRelativePath(fileDto.path, out path))
                {
                    throw new InvalidDataException("嵌入清单包含不安全路径：" + fileDto.path);
                }
                if (byPath.ContainsKey(path))
                {
                    throw new InvalidDataException("嵌入清单包含重复路径：" + path);
                }
                if (totalBytes > Int64.MaxValue - fileDto.size)
                {
                    throw new InvalidDataException("嵌入清单总大小溢出。");
                }
                totalBytes += fileDto.size;
                PayloadFile file = new PayloadFile(path, fileDto.size, fileDto.sha256.ToLowerInvariant());
                files.Add(file);
                byPath.Add(path, file);
            }
            if (files.Count > 100000 || totalBytes > 16L * 1024L * 1024L * 1024L)
            {
                throw new InvalidDataException("嵌入负载超过安全上限。");
            }
            files.Sort(delegate(PayloadFile left, PayloadFile right)
            {
                return StringComparer.Ordinal.Compare(left.Path, right.Path);
            });
            if (!byPath.ContainsKey(target))
            {
                throw new InvalidDataException("嵌入清单没有覆盖启动程序。");
            }

            string computedPackageId = ComputePackageId(files);
            if (!FixedTimeEquals(computedPackageId, dto.packageId.ToLowerInvariant()))
            {
                throw new InvalidDataException("嵌入清单包标识校验失败。");
            }

            PayloadManifest manifest = new PayloadManifest(
                dto.packageId.ToLowerInvariant(),
                target,
                files,
                byPath,
                totalBytes);
            return new LoadedManifest(manifest, raw);
        }

        private static void ValidateArchive(PayloadManifest manifest, bool verifyHashes)
        {
            using (Stream resource = OpenResource(PayloadArchiveResource))
            using (ZipArchive archive = new ZipArchive(resource, ZipArchiveMode.Read, false))
            {
                Dictionary<string, ZipArchiveEntry> entries = GetArchiveEntries(archive, manifest);
                if (verifyHashes)
                {
                    foreach (PayloadFile file in manifest.Files)
                    {
                        using (Stream stream = entries[file.Path].Open())
                        using (SHA256 sha = SHA256.Create())
                        {
                            string digest = ToHex(sha.ComputeHash(stream));
                            if (!FixedTimeEquals(digest, file.Sha256))
                            {
                                throw new InvalidDataException("嵌入压缩包 SHA-256 校验失败：" + file.Path);
                            }
                        }
                    }
                }
            }
        }

        private static Dictionary<string, ZipArchiveEntry> GetArchiveEntries(ZipArchive archive, PayloadManifest manifest)
        {
            Dictionary<string, ZipArchiveEntry> entries = new Dictionary<string, ZipArchiveEntry>(StringComparer.OrdinalIgnoreCase);
            foreach (ZipArchiveEntry entry in archive.Entries)
            {
                if (String.IsNullOrEmpty(entry.Name))
                {
                    throw new InvalidDataException("嵌入压缩包包含目录记录。");
                }
                string normalized;
                if (!TryNormalizeRelativePath(entry.FullName, out normalized))
                {
                    throw new InvalidDataException("嵌入压缩包包含不安全路径：" + entry.FullName);
                }
                int unixFileType = (entry.ExternalAttributes >> 16) & 0xF000;
                if (unixFileType == 0xA000)
                {
                    throw new InvalidDataException("嵌入压缩包包含符号链接：" + normalized);
                }
                PayloadFile expected;
                if (!manifest.FileByPath.TryGetValue(normalized, out expected))
                {
                    throw new InvalidDataException("嵌入压缩包包含清单外文件：" + normalized);
                }
                if (entry.Length != expected.Size || entries.ContainsKey(normalized))
                {
                    throw new InvalidDataException("嵌入压缩包文件长度或唯一性校验失败：" + normalized);
                }
                entries.Add(normalized, entry);
            }
            if (entries.Count != manifest.Files.Count)
            {
                throw new InvalidDataException("嵌入压缩包没有完整覆盖清单。");
            }
            return entries;
        }

        private static Stream OpenResource(string name)
        {
            Stream stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(name);
            if (stream == null)
            {
                throw new MissingManifestResourceException("缺少嵌入资源：" + name);
            }
            return stream;
        }

        private static string ComputePackageId(List<PayloadFile> files)
        {
            using (MemoryStream canonical = new MemoryStream())
            {
                foreach (PayloadFile file in files)
                {
                    WriteCanonical(canonical, file.Path);
                    canonical.WriteByte(0);
                    WriteCanonical(canonical, file.Size.ToString(CultureInfo.InvariantCulture));
                    canonical.WriteByte(0);
                    WriteCanonical(canonical, file.Sha256);
                    canonical.WriteByte((byte)'\n');
                }
                canonical.Position = 0;
                using (SHA256 sha = SHA256.Create())
                {
                    return ToHex(sha.ComputeHash(canonical));
                }
            }
        }

        private static void WriteCanonical(Stream target, string value)
        {
            byte[] bytes = Utf8NoBom.GetBytes(value);
            target.Write(bytes, 0, bytes.Length);
        }

        private static string SafeCombine(string root, string relative)
        {
            string normalized;
            if (!TryNormalizeRelativePath(relative, out normalized))
            {
                throw new InvalidDataException("不安全的相对路径：" + relative);
            }
            string fullRoot = EnsureTrailingSeparator(Path.GetFullPath(root));
            string combined = Path.GetFullPath(Path.Combine(root, normalized.Replace('/', Path.DirectorySeparatorChar)));
            if (!combined.StartsWith(fullRoot, StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidDataException("路径逃逸了安装目录：" + relative);
            }
            return combined;
        }

        private static bool TryNormalizeRelativePath(string value, out string normalized)
        {
            normalized = null;
            if (String.IsNullOrWhiteSpace(value) || value.Length > 1024)
            {
                return false;
            }
            string candidate = value.Replace('\\', '/');
            if (candidate.StartsWith("/", StringComparison.Ordinal)
                || candidate.EndsWith("/", StringComparison.Ordinal)
                || candidate.IndexOf(':') >= 0
                || candidate.IndexOf('\0') >= 0)
            {
                return false;
            }
            string[] parts = candidate.Split('/');
            if (parts.Length == 0)
            {
                return false;
            }
            foreach (string part in parts)
            {
                if (String.IsNullOrEmpty(part)
                    || part == "."
                    || part == ".."
                    || part.EndsWith(".", StringComparison.Ordinal)
                    || part.EndsWith(" ", StringComparison.Ordinal)
                    || part.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0
                    || IsReservedDeviceName(part))
                {
                    return false;
                }
            }
            normalized = String.Join("/", parts);
            return true;
        }

        private static bool IsReservedDeviceName(string segment)
        {
            string name = segment;
            int dot = name.IndexOf('.');
            if (dot >= 0)
            {
                name = name.Substring(0, dot);
            }
            name = name.ToUpperInvariant();
            if (name == "CON" || name == "PRN" || name == "AUX" || name == "NUL")
            {
                return true;
            }
            if (name.Length == 4 && (name.StartsWith("COM", StringComparison.Ordinal) || name.StartsWith("LPT", StringComparison.Ordinal)))
            {
                char suffix = name[3];
                return suffix >= '1' && suffix <= '9';
            }
            return false;
        }

        private static bool IsSafeReleaseName(string value)
        {
            if (String.IsNullOrEmpty(value) || value.Length > 128 || value.StartsWith(".", StringComparison.Ordinal))
            {
                return false;
            }
            for (int index = 0; index < value.Length; index++)
            {
                char character = value[index];
                if (!((character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '-'))
                {
                    return false;
                }
            }
            return true;
        }

        private static string EnsureTrailingSeparator(string path)
        {
            if (path.EndsWith(Path.DirectorySeparatorChar.ToString(), StringComparison.Ordinal)
                || path.EndsWith(Path.AltDirectorySeparatorChar.ToString(), StringComparison.Ordinal))
            {
                return path;
            }
            return path + Path.DirectorySeparatorChar;
        }

        private static bool IsSha256(string value)
        {
            if (String.IsNullOrEmpty(value) || value.Length != 64)
            {
                return false;
            }
            for (int index = 0; index < value.Length; index++)
            {
                char character = value[index];
                if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f')
                    || (character >= 'A' && character <= 'F')))
                {
                    return false;
                }
            }
            return true;
        }

        private static string ToHex(byte[] value)
        {
            StringBuilder builder = new StringBuilder(value.Length * 2);
            for (int index = 0; index < value.Length; index++)
            {
                builder.Append(value[index].ToString("x2", CultureInfo.InvariantCulture));
            }
            return builder.ToString();
        }

        private static bool FixedTimeEquals(string left, string right)
        {
            if (left == null || right == null || left.Length != right.Length)
            {
                return false;
            }
            int difference = 0;
            for (int index = 0; index < left.Length; index++)
            {
                difference |= left[index] ^ right[index];
            }
            return difference == 0;
        }

        private static bool FixedTimeEquals(byte[] left, byte[] right)
        {
            if (left == null || right == null || left.Length != right.Length)
            {
                return false;
            }
            int difference = 0;
            for (int index = 0; index < left.Length; index++)
            {
                difference |= left[index] ^ right[index];
            }
            return difference == 0;
        }

        private static JavaScriptSerializer NewSerializer()
        {
            JavaScriptSerializer serializer = new JavaScriptSerializer();
            serializer.MaxJsonLength = Int32.MaxValue;
            serializer.RecursionLimit = 100;
            return serializer;
        }

        private static void ShowError(bool quiet, Exception error)
        {
            if (quiet)
            {
                return;
            }
            MessageBox.Show(
                "Minecraft Codex Companion 安装失败 / Installation failed:\r\n\r\n" + error.Message,
                "Minecraft Codex Companion",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
        }
    }

    internal sealed class ProgressWindow : Form
    {
        public ProgressWindow()
        {
            Text = "Minecraft Codex Companion";
            Icon = System.Drawing.Icon.ExtractAssociatedIcon(Application.ExecutablePath);
            Width = 500;
            Height = 150;
            StartPosition = FormStartPosition.CenterScreen;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = true;
            ShowInTaskbar = true;

            Label message = new Label();
            message.AutoSize = false;
            message.Left = 24;
            message.Top = 22;
            message.Width = 435;
            message.Height = 38;
            message.Text = "正在校验并安装 Minecraft Codex Companion...\r\nVerifying and installing Minecraft Codex Companion...";
            Controls.Add(message);

            ProgressBar progress = new ProgressBar();
            progress.Left = 24;
            progress.Top = 68;
            progress.Width = 435;
            progress.Height = 22;
            progress.Style = ProgressBarStyle.Marquee;
            progress.MarqueeAnimationSpeed = 25;
            Controls.Add(progress);
        }
    }

    internal sealed class Options
    {
        public bool SelfTest;
        public bool InstallOnly;
        public bool Quiet;
        public string TestRoot;
        public string ResultFile;

        public static Options Parse(string[] args)
        {
            Options result = new Options();
            for (int index = 0; index < args.Length; index++)
            {
                string argument = args[index];
                if (argument == "--self-test")
                {
                    result.SelfTest = true;
                }
                else if (argument == "--install-only")
                {
                    result.InstallOnly = true;
                }
                else if (argument == "--quiet")
                {
                    result.Quiet = true;
                }
                else if (argument == "--test-root" || argument == "--result-file")
                {
                    if (index + 1 >= args.Length)
                    {
                        throw new ArgumentException(argument + " 缺少路径参数。");
                    }
                    string value = args[++index];
                    if (argument == "--test-root")
                    {
                        result.TestRoot = value;
                    }
                    else
                    {
                        result.ResultFile = value;
                    }
                }
                else
                {
                    throw new ArgumentException("未知参数：" + argument);
                }
            }
            if (!String.IsNullOrEmpty(result.TestRoot) && !result.Quiet)
            {
                throw new ArgumentException("--test-root 只能与 --quiet 一起用于离线测试。");
            }
            if (!String.IsNullOrEmpty(result.ResultFile) && String.IsNullOrEmpty(result.TestRoot) && !result.SelfTest)
            {
                throw new ArgumentException("正常安装不会写出本地路径结果；--result-file 仅供离线测试使用。");
            }
            return result;
        }
    }

    internal sealed class PayloadIndexDto
    {
        public int format { get; set; }
        public string packageId { get; set; }
        public string targetExecutable { get; set; }
        public PayloadFileDto[] files { get; set; }
    }

    internal sealed class PayloadFileDto
    {
        public string path { get; set; }
        public long size { get; set; }
        public string sha256 { get; set; }
    }

    internal sealed class InstallPointer
    {
        public int format { get; set; }
        public string packageId { get; set; }
        public string releaseName { get; set; }
    }

    internal sealed class InstallResult
    {
        public int format { get; set; }
        public string status { get; set; }
        public string packageId { get; set; }
        public string releaseName { get; set; }
    }

    internal sealed class FailureResult
    {
        public int format { get; set; }
        public string status { get; set; }
        public string message { get; set; }
    }

    internal sealed class PayloadFile
    {
        public PayloadFile(string path, long size, string sha256)
        {
            Path = path;
            Size = size;
            Sha256 = sha256;
        }

        public string Path { get; private set; }
        public long Size { get; private set; }
        public string Sha256 { get; private set; }
    }

    internal sealed class PayloadManifest
    {
        public PayloadManifest(
            string packageId,
            string targetExecutable,
            List<PayloadFile> files,
            Dictionary<string, PayloadFile> fileByPath,
            long totalBytes)
        {
            PackageId = packageId;
            TargetExecutable = targetExecutable;
            Files = files;
            FileByPath = fileByPath;
            TotalBytes = totalBytes;
        }

        public string PackageId { get; private set; }
        public string TargetExecutable { get; private set; }
        public List<PayloadFile> Files { get; private set; }
        public Dictionary<string, PayloadFile> FileByPath { get; private set; }
        public long TotalBytes { get; private set; }
    }

    internal sealed class LoadedManifest
    {
        public LoadedManifest(PayloadManifest manifest, byte[] rawJson)
        {
            Manifest = manifest;
            RawJson = rawJson;
        }

        public PayloadManifest Manifest { get; private set; }
        public byte[] RawJson { get; private set; }
    }
}
