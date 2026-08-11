using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

internal static class NativeClientWindowQa
{
    private const uint TcmGetCurSel = 0x130B;
    private const uint WmKeyDown = 0x0100;
    private const uint WmKeyUp = 0x0101;
    private const int VkRight = 0x27;

    [StructLayout(LayoutKind.Sequential)]
    private struct Rect
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    private delegate bool EnumWindowsProc(IntPtr window, IntPtr parameter);

    [DllImport("user32.dll")]
    private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr parameter);

    [DllImport("user32.dll")]
    private static extern bool EnumChildWindows(IntPtr parent, EnumWindowsProc callback, IntPtr parameter);

    [DllImport("user32.dll")]
    private static extern bool IsWindowVisible(IntPtr window);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetClassName(IntPtr window, StringBuilder value, int capacity);

    [DllImport("user32.dll")]
    private static extern bool GetWindowRect(IntPtr window, out Rect rectangle);

    [DllImport("user32.dll")]
    private static extern bool MoveWindow(IntPtr window, int x, int y, int width, int height, bool repaint);

    [DllImport("user32.dll")]
    private static extern bool PrintWindow(IntPtr window, IntPtr deviceContext, uint flags);

    [DllImport("user32.dll")]
    private static extern IntPtr SendMessage(IntPtr window, uint message, IntPtr wParam, IntPtr lParam);

    private static int Main(string[] args)
    {
        if (args.Length != 2 && args.Length != 5) return 2;
        int processId;
        if (!Int32.TryParse(args[0], out processId)) return 2;
        string outputDirectory = Path.GetFullPath(args[1]);
        Directory.CreateDirectory(outputDirectory);

        IntPtr mainWindow = IntPtr.Zero;
        IntPtr tabs = IntPtr.Zero;
        DateTime deadline = DateTime.UtcNow.AddSeconds(15);
        while (DateTime.UtcNow < deadline && tabs == IntPtr.Zero)
        {
            mainWindow = FindMainWindow((uint)processId);
            if (mainWindow != IntPtr.Zero) tabs = FindTabControl(mainWindow);
            if (tabs == IntPtr.Zero) System.Threading.Thread.Sleep(150);
        }
        if (mainWindow == IntPtr.Zero) throw new InvalidOperationException("Companion main window not found.");
        if (tabs == IntPtr.Zero) throw new InvalidOperationException("Companion tab control not found.");
        string prefix = "final";
        if (args.Length == 5)
        {
            int width;
            int height;
            if (!Int32.TryParse(args[2], out width) || !Int32.TryParse(args[3], out height)) return 2;
            prefix = args[4];
            Rect current;
            if (!GetWindowRect(mainWindow, out current)) throw new InvalidOperationException("Could not read Companion window bounds.");
            if (!MoveWindow(mainWindow, current.Left, current.Top, width, height, true))
                throw new InvalidOperationException("Could not resize Companion window.");
            System.Threading.Thread.Sleep(350);
        }
        SelectTab(tabs, 0);
        Capture(mainWindow, Path.Combine(outputDirectory, prefix + "-config.png"));
        SelectTab(tabs, 1);
        Capture(mainWindow, Path.Combine(outputDirectory, prefix + "-run.png"));
        SelectTab(tabs, 2);
        Capture(mainWindow, Path.Combine(outputDirectory, prefix + "-antigravity.png"));
        Console.WriteLine("{\"selectedIndex\":2,\"captured\":3,\"prefix\":\"" + prefix + "\"}");
        return 0;
    }

    private static IntPtr FindMainWindow(uint processId)
    {
        IntPtr result = IntPtr.Zero;
        EnumWindows(delegate(IntPtr window, IntPtr parameter)
        {
            uint owner;
            GetWindowThreadProcessId(window, out owner);
            if (owner == processId && IsWindowVisible(window) && FindTabControl(window) != IntPtr.Zero)
            {
                result = window;
                return false;
            }
            return true;
        }, IntPtr.Zero);
        return result;
    }

    private static IntPtr FindTabControl(IntPtr mainWindow)
    {
        IntPtr result = IntPtr.Zero;
        EnumChildWindows(mainWindow, delegate(IntPtr window, IntPtr parameter)
        {
            StringBuilder className = new StringBuilder(256);
            GetClassName(window, className, className.Capacity);
            if (className.ToString().IndexOf("SysTabControl32", StringComparison.OrdinalIgnoreCase) >= 0)
            {
                result = window;
                return false;
            }
            return true;
        }, IntPtr.Zero);
        return result;
    }

    private static void SelectTab(IntPtr tabs, int index)
    {
        for (int attempt = 0; attempt < 4; attempt++)
        {
            int selected = SendMessage(tabs, TcmGetCurSel, IntPtr.Zero, IntPtr.Zero).ToInt32();
            if (selected == index) return;
            SendMessage(tabs, WmKeyDown, new IntPtr(VkRight), new IntPtr(1));
            SendMessage(tabs, WmKeyUp, new IntPtr(VkRight), new IntPtr(1));
            System.Threading.Thread.Sleep(180);
        }
        throw new InvalidOperationException("Could not select Companion tab " + index + ".");
    }

    private static void Capture(IntPtr window, string outputPath)
    {
        Rect rectangle;
        if (!GetWindowRect(window, out rectangle)) throw new InvalidOperationException("Could not read Companion window bounds.");
        int width = rectangle.Right - rectangle.Left;
        int height = rectangle.Bottom - rectangle.Top;
        using (Bitmap bitmap = new Bitmap(width, height))
        using (Graphics graphics = Graphics.FromImage(bitmap))
        {
            IntPtr deviceContext = graphics.GetHdc();
            try
            {
                if (!PrintWindow(window, deviceContext, 2)) throw new InvalidOperationException("PrintWindow failed.");
            }
            finally
            {
                graphics.ReleaseHdc(deviceContext);
            }
            bitmap.Save(outputPath, ImageFormat.Png);
        }
    }
}
