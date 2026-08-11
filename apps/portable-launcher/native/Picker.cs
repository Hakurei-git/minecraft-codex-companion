using System;
using System.IO;
using System.Text;
using System.Windows.Forms;

namespace MinecraftCodexCompanion
{
    internal static class Picker
    {
        [STAThread]
        private static int Main(string[] args)
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);

            if (args.Length >= 1 && args[0] == "self-test")
            {
                if (args.Length >= 2)
                {
                    WriteResult(args[1], "ok");
                }
                return 0;
            }

            if (args.Length < 2)
            {
                return 2;
            }

            string mode = args[0];
            string outputPath = args[1];
            string initialPath = args.Length >= 3 ? args[2] : String.Empty;
            string selectedPath;

            if (mode == "file")
            {
                selectedPath = PickLauncher(initialPath);
            }
            else if (mode == "skin")
            {
                selectedPath = PickSkin(initialPath);
            }
            else if (mode == "folder")
            {
                selectedPath = PickFolder(initialPath);
            }
            else
            {
                return 2;
            }

            if (!String.IsNullOrEmpty(selectedPath))
            {
                WriteResult(outputPath, selectedPath);
            }
            return 0;
        }

        private static string PickLauncher(string initialPath)
        {
            using (OpenFileDialog dialog = new OpenFileDialog())
            {
                dialog.Title = "选择 Minecraft 启动器";
                dialog.Filter = "Minecraft launcher (*.exe;*.jar)|*.exe;*.jar|All files (*.*)|*.*";
                dialog.AutoUpgradeEnabled = true;
                dialog.CheckFileExists = true;
                dialog.CheckPathExists = true;
                dialog.RestoreDirectory = true;
                if (File.Exists(initialPath))
                {
                    dialog.FileName = initialPath;
                }
                else if (Directory.Exists(initialPath))
                {
                    dialog.InitialDirectory = initialPath;
                }
                return ShowDialog(dialog) == DialogResult.OK ? dialog.FileName : String.Empty;
            }
        }

        private static string PickFolder(string initialPath)
        {
            using (FolderBrowserDialog dialog = new FolderBrowserDialog())
            {
                dialog.Description = "选择 Minecraft 根目录";
                dialog.ShowNewFolderButton = false;
                if (Directory.Exists(initialPath))
                {
                    dialog.SelectedPath = initialPath;
                }
                return ShowDialog(dialog) == DialogResult.OK ? dialog.SelectedPath : String.Empty;
            }
        }

        private static string PickSkin(string initialPath)
        {
            using (OpenFileDialog dialog = new OpenFileDialog())
            {
                dialog.Title = "选择 128x64 NPC 皮肤";
                dialog.Filter = "PNG skin (*.png)|*.png";
                dialog.AutoUpgradeEnabled = true;
                dialog.CheckFileExists = true;
                dialog.CheckPathExists = true;
                dialog.RestoreDirectory = true;
                if (File.Exists(initialPath))
                {
                    dialog.FileName = initialPath;
                }
                else if (Directory.Exists(initialPath))
                {
                    dialog.InitialDirectory = initialPath;
                }
                return ShowDialog(dialog) == DialogResult.OK ? dialog.FileName : String.Empty;
            }
        }

        private static DialogResult ShowDialog(CommonDialog dialog)
        {
            return dialog.ShowDialog();
        }

        private static void WriteResult(string outputPath, string value)
        {
            string parent = Path.GetDirectoryName(Path.GetFullPath(outputPath));
            if (!String.IsNullOrEmpty(parent))
            {
                Directory.CreateDirectory(parent);
            }
            File.WriteAllText(outputPath, value, new UTF8Encoding(false));
        }
    }
}
