using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Net;
using System.Text;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;

namespace MinecraftCodexCompanion
{
    internal sealed class ModernTabControl : TabControl
    {
        internal ModernTabControl()
        {
            DrawMode = TabDrawMode.OwnerDrawFixed;
            ItemSize = new Size(126, 40);
            SizeMode = TabSizeMode.Fixed;
            Font = new Font("Microsoft YaHei UI", 9.5F, FontStyle.Regular, GraphicsUnit.Point);
        }

        protected override void OnDrawItem(DrawItemEventArgs e)
        {
            Rectangle bounds = GetTabRect(e.Index);
            bool selected = SelectedIndex == e.Index;
            Color background = selected ? Color.White : Color.FromArgb(238, 243, 240);
            Color foreground = selected ? Color.FromArgb(28, 83, 57) : Color.FromArgb(77, 91, 83);
            using (SolidBrush brush = new SolidBrush(background)) e.Graphics.FillRectangle(brush, bounds);
            Font font = selected ? new Font(Font, FontStyle.Bold) : Font;
            TextRenderer.DrawText(
                e.Graphics,
                TabPages[e.Index].Text,
                font,
                bounds,
                foreground,
                TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis
            );
            if (selected)
            {
                using (SolidBrush accent = new SolidBrush(Color.FromArgb(38, 105, 72)))
                {
                    e.Graphics.FillRectangle(accent, bounds.Left + 18, bounds.Bottom - 3, Math.Max(1, bounds.Width - 36), 3);
                }
            }
            if (selected) font.Dispose();
        }
    }

    internal sealed class FlatGroupBox : GroupBox
    {
        internal FlatGroupBox()
        {
            BackColor = Color.White;
            ForeColor = Color.FromArgb(24, 54, 40);
            Padding = new Padding(14, 24, 14, 12);
            SetStyle(ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw | ControlStyles.UserPaint, true);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            e.Graphics.Clear(BackColor);
            Rectangle border = new Rectangle(0, 11, Math.Max(0, Width - 1), Math.Max(0, Height - 12));
            using (Pen pen = new Pen(Color.FromArgb(207, 219, 212))) e.Graphics.DrawRectangle(pen, border);
            using (SolidBrush accent = new SolidBrush(Color.FromArgb(38, 105, 72))) e.Graphics.FillRectangle(accent, 0, 28, 3, Math.Max(0, Height - 46));
            using (Font titleFont = new Font(Font, FontStyle.Bold))
            {
                Size titleSize = TextRenderer.MeasureText(Text, titleFont);
                Rectangle titleBackground = new Rectangle(13, 1, titleSize.Width + 12, 22);
                using (SolidBrush background = new SolidBrush(BackColor)) e.Graphics.FillRectangle(background, titleBackground);
                TextRenderer.DrawText(e.Graphics, Text, titleFont, new Point(18, 3), ForeColor);
            }
        }
    }

    internal sealed class ServiceStatusBadge : Control
    {
        private bool running;
        private string statusText = "服务未启动";

        internal ServiceStatusBadge()
        {
            Size = new Size(224, 34);
            Font = new Font("Microsoft YaHei UI", 9F, FontStyle.Regular, GraphicsUnit.Point);
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer | ControlStyles.ResizeRedraw | ControlStyles.SupportsTransparentBackColor | ControlStyles.UserPaint, true);
            BackColor = Color.Transparent;
        }

        internal bool Running
        {
            get { return running; }
            set { running = value; Invalidate(); }
        }

        internal string StatusText
        {
            get { return statusText; }
            set { statusText = value ?? String.Empty; Invalidate(); }
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
            Rectangle shape = new Rectangle(0, 0, Math.Max(1, Width - 1), Math.Max(1, Height - 1));
            using (GraphicsPath path = RoundedRectangle(shape, 6))
            using (SolidBrush fill = new SolidBrush(running ? Color.FromArgb(31, 78, 55) : Color.FromArgb(38, 59, 48)))
            using (Pen border = new Pen(running ? Color.FromArgb(71, 145, 101) : Color.FromArgb(77, 99, 87)))
            {
                e.Graphics.FillPath(fill, path);
                e.Graphics.DrawPath(border, path);
            }
            Color dotColor = running ? Color.FromArgb(101, 224, 143) : Color.FromArgb(162, 174, 168);
            using (SolidBrush dot = new SolidBrush(dotColor)) e.Graphics.FillEllipse(dot, 12, (Height - 8) / 2, 8, 8);
            Rectangle textArea = new Rectangle(29, 1, Math.Max(1, Width - 38), Math.Max(1, Height - 2));
            TextRenderer.DrawText(e.Graphics, statusText, Font, textArea, Color.White, TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
        }

        private static GraphicsPath RoundedRectangle(Rectangle rectangle, int radius)
        {
            int diameter = radius * 2;
            GraphicsPath path = new GraphicsPath();
            path.AddArc(rectangle.Left, rectangle.Top, diameter, diameter, 180, 90);
            path.AddArc(rectangle.Right - diameter, rectangle.Top, diameter, diameter, 270, 90);
            path.AddArc(rectangle.Right - diameter, rectangle.Bottom - diameter, diameter, diameter, 0, 90);
            path.AddArc(rectangle.Left, rectangle.Bottom - diameter, diameter, diameter, 90, 90);
            path.CloseFigure();
            return path;
        }
    }

    internal sealed class PixelPreviewBox : PictureBox
    {
        internal PixelPreviewBox()
        {
            BackColor = Color.FromArgb(240, 244, 242);
            BorderStyle = BorderStyle.FixedSingle;
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            e.Graphics.Clear(BackColor);
            if (Image != null)
            {
                e.Graphics.InterpolationMode = InterpolationMode.NearestNeighbor;
                e.Graphics.PixelOffsetMode = PixelOffsetMode.Half;
                float scale = Math.Min((float)ClientSize.Width / Image.Width, (float)ClientSize.Height / Image.Height);
                int width = Math.Max(1, (int)Math.Floor(Image.Width * scale));
                int height = Math.Max(1, (int)Math.Floor(Image.Height * scale));
                int left = (ClientSize.Width - width) / 2;
                int top = (ClientSize.Height - height) / 2;
                e.Graphics.DrawImage(Image, new Rectangle(left, top, width, height));
            }
            ControlPaint.DrawBorder(e.Graphics, ClientRectangle, Color.FromArgb(190, 205, 197), ButtonBorderStyle.Solid);
        }
    }

    internal sealed class LocalApiClient
    {
        private readonly string endpoint;
        private readonly string session;
        private readonly JavaScriptSerializer json = new JavaScriptSerializer();

        internal LocalApiClient(string endpoint, string session)
        {
            Uri uri;
            if (!Uri.TryCreate(endpoint, UriKind.Absolute, out uri) || uri.Scheme != Uri.UriSchemeHttp || !uri.IsLoopback)
            {
                throw new ArgumentException("独立客户端只能连接本机 HTTP 服务。");
            }
            if (String.IsNullOrWhiteSpace(session))
            {
                throw new ArgumentException("本机会话凭据缺失。");
            }
            this.endpoint = endpoint.TrimEnd('/');
            this.session = session;
            json.MaxJsonLength = 4 * 1024 * 1024;
        }

        internal Dictionary<string, object> Get(string path)
        {
            return Parse(Request("GET", path, null));
        }

        internal Dictionary<string, object> Post(string path, object body)
        {
            return Parse(Request("POST", path, json.Serialize(body ?? new Dictionary<string, object>())));
        }

        internal byte[] GetBytes(string path)
        {
            using (WebClient client = CreateClient())
            {
                return client.DownloadData(endpoint + path);
            }
        }

        private WebClient CreateClient()
        {
            WebClient client = new WebClient();
            client.Proxy = null;
            client.Encoding = Encoding.UTF8;
            client.Headers["x-companion-session"] = session;
            return client;
        }

        private string Request(string method, string path, string body)
        {
            try
            {
                using (WebClient client = CreateClient())
                {
                    if (method == "POST")
                    {
                        client.Headers[HttpRequestHeader.ContentType] = "application/json; charset=utf-8";
                        return client.UploadString(endpoint + path, "POST", body ?? "{}");
                    }
                    return client.DownloadString(endpoint + path);
                }
            }
            catch (WebException error)
            {
                string message = error.Message;
                if (error.Response != null)
                {
                    try
                    {
                        using (Stream stream = error.Response.GetResponseStream())
                        using (StreamReader reader = new StreamReader(stream, Encoding.UTF8))
                        {
                            Dictionary<string, object> response = Parse(reader.ReadToEnd());
                            string remote = JsonValue.String(response, "error");
                            if (!String.IsNullOrWhiteSpace(remote)) message = remote;
                        }
                    }
                    catch
                    {
                    }
                }
                throw new InvalidOperationException(message, error);
            }
        }

        private Dictionary<string, object> Parse(string text)
        {
            object parsed = json.DeserializeObject(text);
            Dictionary<string, object> result = parsed as Dictionary<string, object>;
            if (result == null) throw new InvalidDataException("本地服务返回了无效 JSON。");
            return result;
        }
    }

    internal static class JsonValue
    {
        internal static Dictionary<string, object> Object(Dictionary<string, object> source, string key)
        {
            object value;
            return source != null && source.TryGetValue(key, out value) ? value as Dictionary<string, object> : null;
        }

        internal static object[] Array(Dictionary<string, object> source, string key)
        {
            object value;
            if (source == null || !source.TryGetValue(key, out value) || value == null) return new object[0];
            object[] array = value as object[];
            if (array != null) return array;
            System.Collections.ArrayList list = value as System.Collections.ArrayList;
            return list == null ? new object[0] : list.ToArray();
        }

        internal static string String(Dictionary<string, object> source, string key)
        {
            object value;
            return source != null && source.TryGetValue(key, out value) && value != null ? Convert.ToString(value) : global::System.String.Empty;
        }

        internal static bool Boolean(Dictionary<string, object> source, string key, bool fallback)
        {
            object value;
            if (source == null || !source.TryGetValue(key, out value) || value == null) return fallback;
            try { return Convert.ToBoolean(value); } catch { return fallback; }
        }

        internal static int Integer(Dictionary<string, object> source, string key, int fallback)
        {
            object value;
            if (source == null || !source.TryGetValue(key, out value) || value == null) return fallback;
            try { return Convert.ToInt32(value); } catch { return fallback; }
        }
    }

    internal sealed class CompanionClientForm : Form
    {
        private static readonly Color Forest = Color.FromArgb(38, 105, 72);
        private static readonly Color ForestDark = Color.FromArgb(20, 48, 35);
        private static readonly Color Canvas = Color.FromArgb(244, 247, 245);
        private static readonly Color Border = Color.FromArgb(199, 211, 204);
        private static readonly Color Danger = Color.FromArgb(166, 52, 52);

        private readonly LocalApiClient api;
        private readonly Timer refreshTimer = new Timer();
        private readonly ToolTip hints = new ToolTip();
        private readonly List<Control> actionControls = new List<Control>();

        private Label payloadStatus;
        private Label headerTitle;
        private TableLayoutPanel headerLayout;
        private TableLayoutPanel headerBrand;
        private ServiceStatusBadge serviceStatus;
        private Label saveStatus;
        private Label operationStatus;
        private Label stateDirectory;
        private Label connectionAddress;
        private Label mcpStatus;
        private StatusStrip footer;
        private ToolStripStatusLabel footerMessage;
        private ToolStripStatusLabel footerSpring;
        private ToolStripStatusLabel footerMode;
        private ListView eventsList;
        private FlowLayoutPanel configFlow;
        private GroupBox personaGroup;
        private Panel personaFields;
        private PictureBox skinPreview;
        private Label skinStatus;
        private TextBox promptBox;
        private Panel antigravityCommandsPanel;
        private GroupBox antigravityPromptGroup;

        private TextBox launcherPath;
        private TextBox launcherArguments;
        private TextBox minecraftRoot;
        private ComboBox sourceVersion;
        private TextBox targetVersion;
        private TextBox playerName;
        private TextBox companionName;
        private NumericUpDown port;
        private TextBox antigravityConfigPath;
        private TextBox antigravityConversationTitle;
        private CheckBox freeChatEnabled;
        private RadioButton activeProvider;
        private RadioButton antigravityProvider;
        private CheckBox smartAiEnabled;
        private NumericUpDown tokenBudget;
        private Label actionModeHint;
        private RadioButton inheritPersona;
        private RadioButton customPersona;
        private TextBox personaDisplayName;
        private TextBox personaPersonality;
        private TextBox personaSpeakingStyle;
        private TextBox personaMemoryNotes;

        private bool busy;
        private bool loading;
        private bool refreshing;
        private bool targetTouched;
        private string npcSkinMode = "default";
        private bool customSkinAvailable;

        internal CompanionClientForm(string endpoint, string session)
        {
            api = new LocalApiClient(endpoint, session);
            Text = "Minecraft Codex Companion";
            Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
            StartPosition = FormStartPosition.CenterScreen;
            MinimumSize = new Size(980, 700);
            Size = new Size(1160, 820);
            BackColor = Canvas;
            Font = new Font("Microsoft YaHei UI", 9F, FontStyle.Regular, GraphicsUnit.Point);
            AutoScaleMode = AutoScaleMode.Dpi;
            DoubleBuffered = true;

            BuildInterface();
            Shown += async delegate { await InitializeAsync(); };
            FormClosed += delegate
            {
                refreshTimer.Stop();
                hints.Dispose();
            };
            refreshTimer.Interval = 4000;
            refreshTimer.Tick += async delegate
            {
                if (!busy && !refreshing) await RefreshAsync(false);
            };
        }

        private void BuildInterface()
        {
            TableLayoutPanel root = new TableLayoutPanel();
            root.Dock = DockStyle.Fill;
            root.RowCount = 3;
            root.ColumnCount = 1;
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 25F));
            Controls.Add(root);

            root.Controls.Add(BuildHeader(), 0, 0);
            ModernTabControl tabs = new ModernTabControl();
            tabs.Dock = DockStyle.Fill;
            tabs.Padding = new Point(18, 7);
            tabs.Controls.Add(BuildConfigTab());
            tabs.Controls.Add(BuildRunTab());
            tabs.Controls.Add(BuildAntigravityTab());
            root.Controls.Add(tabs, 0, 1);

            footer = new StatusStrip();
            footer.SizingGrip = false;
            footer.BackColor = Color.FromArgb(232, 238, 234);
            footerMessage = new ToolStripStatusLabel("正在初始化独立客户端...");
            footerSpring = new ToolStripStatusLabel();
            footerSpring.Spring = true;
            footerMode = new ToolStripStatusLabel("原生客户端");
            footer.Items.AddRange(new ToolStripItem[] { footerMessage, footerSpring, footerMode });
            root.Controls.Add(footer, 0, 2);
        }

        private Control BuildHeader()
        {
            TableLayoutPanel header = new TableLayoutPanel();
            headerLayout = header;
            header.Dock = DockStyle.Fill;
            header.AutoSize = true;
            header.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            header.MinimumSize = new Size(0, 72);
            header.BackColor = ForestDark;
            header.Padding = new Padding(24, 9, 22, 9);
            header.ColumnCount = 3;
            header.RowCount = 1;
            header.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100F));
            header.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 244F));
            header.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 94F));
            header.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));

            TableLayoutPanel brand = new TableLayoutPanel();
            headerBrand = brand;
            brand.Dock = DockStyle.Fill;
            brand.AutoSize = true;
            brand.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            brand.ColumnCount = 1;
            brand.RowCount = 2;
            brand.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100F));
            brand.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            brand.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            Label title = new Label();
            headerTitle = title;
            title.Text = "Minecraft Codex Companion";
            title.ForeColor = Color.White;
            title.Font = new Font("Segoe UI", 14F, FontStyle.Bold);
            title.AutoSize = true;
            title.Anchor = AnchorStyles.Left;
            title.Margin = new Padding(0, 0, 0, 2);
            payloadStatus = new Label();
            payloadStatus.Text = "正在检查便携运行时";
            payloadStatus.ForeColor = Color.FromArgb(181, 209, 193);
            payloadStatus.Font = new Font("Microsoft YaHei UI", 8.5F, FontStyle.Regular);
            payloadStatus.AutoSize = true;
            payloadStatus.Anchor = AnchorStyles.Left;
            payloadStatus.Margin = new Padding(1, 0, 0, 0);
            brand.Controls.Add(title, 0, 0);
            brand.Controls.Add(payloadStatus, 0, 1);
            header.Controls.Add(brand, 0, 0);

            serviceStatus = new ServiceStatusBadge();
            serviceStatus.Anchor = AnchorStyles.None;
            serviceStatus.Margin = new Padding(8, 0, 12, 0);
            header.Controls.Add(serviceStatus, 1, 0);

            Button refresh = MakeButton("刷新", false);
            refresh.Size = new Size(82, 34);
            refresh.Anchor = AnchorStyles.None;
            refresh.Margin = new Padding(6, 0, 0, 0);
            refresh.Click += async delegate { await RefreshAsync(false); };
            actionControls.Add(refresh);
            header.Controls.Add(refresh, 2, 0);
            return header;
        }

        private TabPage BuildConfigTab()
        {
            TabPage page = new TabPage("配置");
            page.BackColor = Canvas;
            page.Padding = new Padding(10);
            configFlow = new FlowLayoutPanel();
            configFlow.Dock = DockStyle.Fill;
            configFlow.AutoScroll = true;
            configFlow.FlowDirection = FlowDirection.TopDown;
            configFlow.WrapContents = false;
            configFlow.Padding = new Padding(4, 2, 4, 4);

            Panel configState = new Panel();
            configState.Height = 22;
            configState.Margin = new Padding(4, 0, 4, 0);
            saveStatus = MakeLabel("尚未保存", false);
            saveStatus.ForeColor = Color.FromArgb(91, 105, 97);
            saveStatus.Top = 2;
            saveStatus.Anchor = AnchorStyles.Top | AnchorStyles.Right;
            configState.Controls.Add(saveStatus);
            configState.Resize += delegate { saveStatus.Left = configState.ClientSize.Width - saveStatus.Width - 8; };
            GroupBox local = BuildLocalConfigGroup();
            personaGroup = BuildPersonaGroup();
            GroupBox appearance = BuildAppearanceGroup();
            configFlow.Controls.Add(configState);
            configFlow.Controls.Add(local);
            configFlow.Controls.Add(personaGroup);
            configFlow.Controls.Add(appearance);
            configFlow.Resize += delegate { ResizeConfigGroups(); };
            page.Controls.Add(configFlow);
            return page;
        }

        private GroupBox BuildLocalConfigGroup()
        {
            GroupBox group = MakeGroup("本机配置");
            group.Height = 298;
            TableLayoutPanel grid = MakeGrid(4, 7);
            grid.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 124F));
            grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50F));
            grid.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 112F));
            grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50F));
            for (int row = 0; row < 7; row++) grid.RowStyles.Add(new RowStyle(SizeType.Absolute, 35F));

            launcherPath = MakeTextBox();
            Button browseLauncher = MakeButton("选择文件...", false);
            browseLauncher.Click += delegate { BrowseLauncher(); };
            hints.SetToolTip(launcherPath, "选择桌面上的 HMCL.exe 或 HMCL.jar");
            hints.SetToolTip(browseLauncher, "选择桌面上的 HMCL.exe 或 HMCL.jar");
            AddWidePathRow(grid, 0, "HMCL 启动器", launcherPath, browseLauncher);

            minecraftRoot = MakeTextBox();
            Button browseRoot = MakeButton("浏览...", false);
            browseRoot.Click += async delegate { await BrowseMinecraftRootAsync(); };
            AddWidePathRow(grid, 1, "Minecraft 根目录", minecraftRoot, browseRoot);

            sourceVersion = new ComboBox();
            sourceVersion.DropDownStyle = ComboBoxStyle.DropDownList;
            sourceVersion.Dock = DockStyle.Fill;
            sourceVersion.FlatStyle = FlatStyle.Standard;
            sourceVersion.BackColor = Color.White;
            sourceVersion.Margin = new Padding(4, 4, 8, 4);
            sourceVersion.SelectedIndexChanged += delegate
            {
                if (loading) return;
                MarkDirty();
                if (!targetTouched || String.IsNullOrWhiteSpace(targetVersion.Text))
                {
                    targetVersion.Text = sourceVersion.SelectedItem == null ? String.Empty : sourceVersion.SelectedItem + "-Codex";
                    targetTouched = false;
                }
            };
            targetVersion = MakeTextBox();
            targetVersion.TextChanged += delegate { if (!loading) targetTouched = true; };
            AddPairRow(grid, 2, "源实例", sourceVersion, "Codex 实例名", targetVersion);

            playerName = MakeTextBox();
            companionName = MakeTextBox();
            AddPairRow(grid, 3, "游戏玩家名", playerName, "NPC 名称", companionName);

            port = new NumericUpDown();
            port.Minimum = 1024;
            port.Maximum = 65535;
            port.Value = 8765;
            port.Dock = DockStyle.Fill;
            port.BorderStyle = BorderStyle.FixedSingle;
            port.Margin = new Padding(4, 4, 8, 4);
            port.ValueChanged += delegate { MarkDirty(); };
            AddPairRow(grid, 4, "服务端口", port, "", new Label());

            launcherArguments = MakeTextBox();
            AddWideRow(grid, 5, "启动参数（可选）", launcherArguments);

            antigravityConfigPath = MakeTextBox();
            AddWideRow(grid, 6, "反重力 MCP 配置", antigravityConfigPath);

            group.Controls.Add(grid);
            return group;
        }

        private GroupBox BuildPersonaGroup()
        {
            GroupBox group = MakeGroup("人格设定");
            group.Height = 92;
            Panel content = new Panel();
            content.Dock = DockStyle.Fill;
            content.Padding = new Padding(12, 8, 12, 10);

            FlowLayoutPanel modes = new FlowLayoutPanel();
            modes.Dock = DockStyle.Top;
            modes.Height = 31;
            modes.FlowDirection = FlowDirection.LeftToRight;
            inheritPersona = new RadioButton();
            inheritPersona.Text = "继承 Codex / Claude / 反重力现有人格";
            inheritPersona.AutoSize = true;
            inheritPersona.Checked = true;
            customPersona = new RadioButton();
            customPersona.Text = "叠加 Minecraft 自定义人格";
            customPersona.AutoSize = true;
            modes.Controls.Add(inheritPersona);
            modes.Controls.Add(customPersona);
            content.Controls.Add(modes);

            personaFields = new Panel();
            personaFields.Dock = DockStyle.Fill;
            personaFields.Padding = new Padding(0, 33, 0, 0);
            TableLayoutPanel grid = MakeGrid(2, 4);
            grid.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 105F));
            grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100F));
            grid.RowStyles.Add(new RowStyle(SizeType.Absolute, 38F));
            grid.RowStyles.Add(new RowStyle(SizeType.Absolute, 62F));
            grid.RowStyles.Add(new RowStyle(SizeType.Absolute, 62F));
            grid.RowStyles.Add(new RowStyle(SizeType.Absolute, 76F));
            personaDisplayName = MakeTextBox();
            personaPersonality = MakeMultilineTextBox();
            personaSpeakingStyle = MakeMultilineTextBox();
            personaMemoryNotes = MakeMultilineTextBox();
            AddField(grid, 0, "游戏内称呼", personaDisplayName);
            AddField(grid, 1, "人格与行为", personaPersonality);
            AddField(grid, 2, "说话风格", personaSpeakingStyle);
            AddField(grid, 3, "长期备注", personaMemoryNotes);
            personaFields.Controls.Add(grid);
            personaFields.Visible = false;
            content.Controls.Add(personaFields);

            inheritPersona.CheckedChanged += delegate { UpdatePersonaLayout(); };
            customPersona.CheckedChanged += delegate { UpdatePersonaLayout(); };
            group.Controls.Add(content);
            return group;
        }

        private GroupBox BuildAppearanceGroup()
        {
            GroupBox group = MakeGroup("NPC 外观与聊天");
            group.Height = 205;
            Panel panel = new Panel();
            panel.Dock = DockStyle.Fill;
            panel.Padding = new Padding(14);

            skinPreview = new PixelPreviewBox();
            skinPreview.Location = new Point(16, 25);
            skinPreview.Size = new Size(180, 88);
            panel.Controls.Add(skinPreview);

            Label skinTitle = MakeLabel("NPC 皮肤", true);
            skinTitle.Location = new Point(214, 27);
            panel.Controls.Add(skinTitle);
            skinStatus = MakeLabel("当前白发猫娘皮肤", false);
            skinStatus.Location = new Point(214, 51);
            panel.Controls.Add(skinStatus);
            Button chooseSkin = MakeButton("选择 PNG", false);
            chooseSkin.Location = new Point(214, 78);
            chooseSkin.Click += async delegate { await ChooseSkinAsync(); };
            Button defaultSkin = MakeButton("恢复默认", false);
            defaultSkin.Location = new Point(316, 78);
            defaultSkin.Click += async delegate { await RestoreDefaultSkinAsync(); };
            panel.Controls.Add(chooseSkin);
            panel.Controls.Add(defaultSkin);

            freeChatEnabled = new CheckBox();
            freeChatEnabled.Text = "启用自由聊天";
            freeChatEnabled.AutoSize = true;
            freeChatEnabled.Location = new Point(470, 28);
            freeChatEnabled.CheckedChanged += delegate { MarkDirty(); };
            panel.Controls.Add(freeChatEnabled);
            Label targetLabel = MakeLabel("普通聊天响应端", true);
            targetLabel.Location = new Point(470, 54);
            panel.Controls.Add(targetLabel);
            activeProvider = new RadioButton();
            activeProvider.Text = "Codex / Claude";
            activeProvider.AutoSize = true;
            activeProvider.Location = new Point(470, 78);
            activeProvider.CheckedChanged += delegate { MarkDirty(); };
            antigravityProvider = new RadioButton();
            antigravityProvider.Text = "反重力 MCP";
            antigravityProvider.AutoSize = true;
            antigravityProvider.Location = new Point(600, 78);
            antigravityProvider.CheckedChanged += delegate { MarkDirty(); };
            panel.Controls.Add(activeProvider);
            panel.Controls.Add(antigravityProvider);

            Label actionModeLabel = MakeLabel("智能 AI 任务理解", true);
            actionModeLabel.Location = new Point(470, 107);
            panel.Controls.Add(actionModeLabel);
            smartAiEnabled = new CheckBox();
            smartAiEnabled.Text = "启用智能 AI";
            smartAiEnabled.AutoSize = true;
            smartAiEnabled.Location = new Point(470, 132);
            smartAiEnabled.CheckedChanged += delegate
            {
                UpdateActionModeHint();
                MarkDirty();
            };
            panel.Controls.Add(smartAiEnabled);

            Label tokenBudgetLabel = MakeLabel("单次 AI 输出 token", true);
            tokenBudgetLabel.Location = new Point(696, 107);
            panel.Controls.Add(tokenBudgetLabel);
            tokenBudget = new NumericUpDown();
            tokenBudget.Minimum = 128;
            tokenBudget.Maximum = 4096;
            tokenBudget.Increment = 128;
            tokenBudget.Value = 512;
            tokenBudget.Location = new Point(696, 128);
            tokenBudget.Size = new Size(118, 28);
            tokenBudget.ValueChanged += delegate { MarkDirty(); };
            panel.Controls.Add(tokenBudget);

            actionModeHint = MakeLabel("关闭时动作只走本地规则；自由聊天由独立开关控制。", false);
            actionModeHint.AutoSize = false;
            actionModeHint.Location = new Point(470, 162);
            actionModeHint.Size = new Size(520, 22);
            panel.Controls.Add(actionModeHint);

            Button save = MakeButton("保存配置", true);
            save.Size = new Size(110, 34);
            save.Anchor = AnchorStyles.Top | AnchorStyles.Right;
            save.Location = new Point(group.Width - 145, 72);
            save.Click += async delegate { await SaveAsync(); };
            actionControls.Add(save);
            actionControls.Add(chooseSkin);
            actionControls.Add(defaultSkin);
            panel.Resize += delegate { save.Left = panel.ClientSize.Width - save.Width - 18; };
            panel.Controls.Add(save);
            group.Controls.Add(panel);
            return group;
        }

        private TabPage BuildRunTab()
        {
            TabPage page = new TabPage("运行");
            page.BackColor = Canvas;
            page.Padding = new Padding(18);
            TableLayoutPanel root = new TableLayoutPanel();
            root.Dock = DockStyle.Fill;
            root.RowCount = 3;
            root.ColumnCount = 1;
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 112F));
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 130F));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));

            Panel state = new Panel();
            state.Dock = DockStyle.Fill;
            operationStatus = MakeLabel("就绪", true);
            operationStatus.Font = new Font(Font.FontFamily, 13F, FontStyle.Bold);
            operationStatus.Location = new Point(8, 8);
            Label stateHint = MakeLabel("安装实例、启动控制服务和打开 HMCL 均在此客户端完成。", false);
            stateHint.Location = new Point(9, 39);
            state.Controls.Add(operationStatus);
            state.Controls.Add(stateHint);
            root.Controls.Add(state, 0, 0);

            GroupBox actions = MakeGroup("常用操作");
            actions.Dock = DockStyle.Fill;
            FlowLayoutPanel commands = new FlowLayoutPanel();
            commands.Dock = DockStyle.Top;
            commands.Height = 48;
            commands.Padding = new Padding(12, 6, 12, 4);
            commands.Controls.Add(ActionButton("一键准备并启动", "/api/prepare", true, true));
            commands.Controls.Add(ActionButton("安装 / 更新实例", "/api/install", true, false));
            commands.Controls.Add(ActionButton("启动服务", "/api/service/start", true, false));
            commands.Controls.Add(ActionButton("打开启动器", "/api/launcher/start", true, false));
            Button stop = ActionButton("停止服务", "/api/service/stop", false, false);
            stop.ForeColor = Danger;
            commands.Controls.Add(stop);
            commands.Controls.Add(ActionButton("高级控制台（浏览器）", "/api/dashboard/open", false, false));
            actions.Controls.Add(commands);

            TableLayoutPanel facts = new TableLayoutPanel();
            facts.Dock = DockStyle.Bottom;
            facts.Height = 45;
            facts.ColumnCount = 2;
            facts.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 55F));
            facts.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 45F));
            stateDirectory = MakeLabel("状态目录：", false);
            connectionAddress = MakeLabel("控制服务：", false);
            facts.Controls.Add(stateDirectory, 0, 0);
            facts.Controls.Add(connectionAddress, 1, 0);
            actions.Controls.Add(facts);
            root.Controls.Add(actions, 0, 1);

            GroupBox activity = MakeGroup("活动记录");
            activity.Dock = DockStyle.Fill;
            eventsList = new ListView();
            eventsList.Dock = DockStyle.Fill;
            eventsList.View = View.Details;
            eventsList.FullRowSelect = true;
            eventsList.GridLines = true;
            eventsList.BorderStyle = BorderStyle.None;
            eventsList.HeaderStyle = ColumnHeaderStyle.Nonclickable;
            eventsList.Columns.Add("时间", 90);
            eventsList.Columns.Add("级别", 80);
            eventsList.Columns.Add("内容", 700);
            activity.Controls.Add(eventsList);
            root.Controls.Add(activity, 0, 2);
            page.Controls.Add(root);
            return page;
        }

        private TabPage BuildAntigravityTab()
        {
            TabPage page = new TabPage("反重力");
            page.BackColor = Canvas;
            page.Padding = new Padding(18);
            TableLayoutPanel root = new TableLayoutPanel();
            root.Dock = DockStyle.Fill;
            root.RowCount = 3;
            root.ColumnCount = 1;
            // Keep the action row, conversation-title editor, and binding status
            // in separate vertical bands.  The previous 70 px row ended above
            // the title TextBox (Y=52, H=28), so the following GroupBox painted
            // over it at common Windows DPI settings.
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 112F));
            root.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            root.RowStyles.Add(new RowStyle(SizeType.Absolute, 70F));

            Panel commands = new Panel();
            commands.Dock = DockStyle.Fill;
            antigravityCommandsPanel = commands;
            Button install = ActionButton("写入 MCP 配置", "/api/antigravity/install", true, true);
            install.Location = new Point(4, 8);
            Button test = ActionButton("测试 MCP 通道", "/api/mcp/test", true, false);
            test.Location = new Point(154, 8);
            Button bind = ActionButton("按标题绑定会话", "/api/antigravity/bind", true, true);
            bind.Location = new Point(304, 8);
            Button recover = ActionButton("解除会话卡住", "/api/antigravity/recover", true, false);
            recover.Location = new Point(454, 8);
            Button copy = MakeButton("复制备用提示词", false);
            copy.Size = new Size(140, 32);
            copy.Location = new Point(604, 8);
            copy.Click += delegate { CopyPrompt(); };
            actionControls.Add(copy);
            mcpStatus = MakeLabel("未测试", true);
            mcpStatus.AutoSize = false;
            mcpStatus.TextAlign = ContentAlignment.MiddleRight;
            mcpStatus.Location = new Point(8, 84);
            mcpStatus.Size = new Size(730, 22);
            mcpStatus.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            Label titleLabel = MakeLabel("目标会话标题", false);
            titleLabel.Location = new Point(8, 58);
            antigravityConversationTitle = MakeTextBox();
            antigravityConversationTitle.Location = new Point(118, 52);
            antigravityConversationTitle.Size = new Size(620, 28);
            antigravityConversationTitle.Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right;
            commands.Resize += delegate
            {
                antigravityConversationTitle.Width = Math.Max(260, commands.ClientSize.Width - antigravityConversationTitle.Left - 12);
                mcpStatus.Width = Math.Max(260, commands.ClientSize.Width - 20);
            };
            hints.SetToolTip(antigravityConversationTitle, "必须与反重力任务列表中显示的标题完全一致");
            commands.Controls.Add(install);
            commands.Controls.Add(test);
            commands.Controls.Add(bind);
            commands.Controls.Add(recover);
            commands.Controls.Add(copy);
            commands.Controls.Add(mcpStatus);
            commands.Controls.Add(titleLabel);
            commands.Controls.Add(antigravityConversationTitle);
            root.Controls.Add(commands, 0, 0);

            GroupBox prompt = MakeGroup("自动回复说明与手动备用提示词");
            prompt.Dock = DockStyle.Fill;
            antigravityPromptGroup = prompt;
            promptBox = new TextBox();
            promptBox.Dock = DockStyle.Fill;
            promptBox.Multiline = true;
            promptBox.ReadOnly = true;
            promptBox.ScrollBars = ScrollBars.Vertical;
            promptBox.BackColor = Color.White;
            promptBox.BorderStyle = BorderStyle.None;
            promptBox.Font = new Font("Consolas", 9F);
            prompt.Controls.Add(promptBox);
            root.Controls.Add(prompt, 0, 1);

            Label rule = MakeLabel("游戏内回复规则：人格闲聊、任务反馈与最终回答都必须通过 mc_chat 发进 Minecraft；只写在反重力窗口里的内容不会出现在游戏中。", false);
            rule.Dock = DockStyle.Fill;
            rule.Padding = new Padding(12, 18, 12, 8);
            root.Controls.Add(rule, 0, 2);
            page.Controls.Add(root);
            return page;
        }

        internal bool ValidateAntigravityLayoutForTest(Size windowSize)
        {
            Size = windowSize;
            CreateControl();
            PerformLayoutTree(this);
            PerformLayoutTree(this);
            if (antigravityCommandsPanel == null || antigravityPromptGroup == null
                || antigravityConversationTitle == null || mcpStatus == null) return false;

            int promptTop = antigravityPromptGroup.Top;
            int titleBottom = antigravityCommandsPanel.Top + antigravityConversationTitle.Bottom;
            int statusBottom = antigravityCommandsPanel.Top + mcpStatus.Bottom;
            return antigravityConversationTitle.Width >= 260
                && titleBottom + 4 <= promptTop
                && statusBottom + 2 <= promptTop;
        }

        internal bool ValidateHeaderLayoutForTest(Size windowSize, float textScale)
        {
            Size = windowSize;
            CreateControl();
            headerTitle.Font = new Font("Segoe UI", 14F * textScale, FontStyle.Bold);
            payloadStatus.Font = new Font("Microsoft YaHei UI", 8.5F * textScale, FontStyle.Regular);
            PerformLayoutTree(this);
            PerformLayoutTree(this);
            if (headerLayout == null || headerBrand == null || headerTitle == null || payloadStatus == null) return false;

            return headerBrand.ClientRectangle.Contains(headerTitle.Bounds)
                && headerBrand.ClientRectangle.Contains(payloadStatus.Bounds)
                && headerTitle.Bottom <= payloadStatus.Top
                && headerTitle.Width >= headerTitle.PreferredWidth
                && payloadStatus.Width >= payloadStatus.PreferredWidth
                && headerBrand.Bottom <= headerLayout.ClientSize.Height - headerLayout.Padding.Bottom;
        }

        private static void PerformLayoutTree(Control root)
        {
            root.PerformLayout();
            foreach (Control child in root.Controls) PerformLayoutTree(child);
        }

        private async Task InitializeAsync()
        {
            SetBusy(true, "正在读取本机配置...");
            try
            {
                await RefreshAsync(true);
                refreshTimer.Start();
                ShowFooter("独立客户端已就绪");
            }
            catch (Exception error)
            {
                ShowError(error);
            }
            finally
            {
                SetBusy(false, "就绪");
            }
        }

        private async Task RefreshAsync(bool initial)
        {
            if (refreshing) return;
            refreshing = true;
            try
            {
                Dictionary<string, object> data = await Task.Run(delegate { return api.Get("/api/bootstrap"); });
                ApplyBootstrap(data, initial);
            }
            catch (Exception error)
            {
                if (initial) throw;
                ShowFooter("刷新失败：" + error.Message);
            }
            finally
            {
                refreshing = false;
            }
        }

        private void ApplyBootstrap(Dictionary<string, object> data, bool initial)
        {
            Dictionary<string, object> payload = JsonValue.Object(data, "payload");
            bool valid = JsonValue.Boolean(payload, "valid", false);
            payloadStatus.Text = valid ? "便携运行时完整 · 原生客户端" : JsonValue.String(payload, "error");
            stateDirectory.Text = "状态目录：" + JsonValue.String(data, "stateDirectory");
            promptBox.Text = JsonValue.String(data, "prompt");
            UpdateService(
                JsonValue.Object(data, "service"),
                JsonValue.Object(data, "readiness")
            );
            RenderEvents(JsonValue.Array(data, "events"));
            string operation = JsonValue.String(data, "operation");
            if (!busy) operationStatus.Text = String.IsNullOrWhiteSpace(operation) ? "就绪" : operation;

            if (initial)
            {
                Dictionary<string, object> config = JsonValue.Object(data, "config");
                FillConfig(config);
                FillInstances(JsonValue.Array(data, "instances"), JsonValue.String(config, "sourceVersion"));
                Dictionary<string, object> skin = JsonValue.Object(data, "skin");
                customSkinAvailable = JsonValue.Boolean(skin, "customAvailable", false);
                BeginSkinPreviewLoad(npcSkinMode == "custom" && customSkinAvailable);
                saveStatus.Text = "配置已加载";
            }
        }

        private void FillConfig(Dictionary<string, object> config)
        {
            loading = true;
            try
            {
                launcherPath.Text = JsonValue.String(config, "launcherPath");
                launcherArguments.Text = JsonValue.String(config, "launcherArguments");
                minecraftRoot.Text = JsonValue.String(config, "minecraftRoot");
                targetVersion.Text = JsonValue.String(config, "targetVersion");
                playerName.Text = JsonValue.String(config, "playerName");
                companionName.Text = JsonValue.String(config, "companionName");
                int configuredPort = JsonValue.Integer(config, "port", 8765);
                port.Value = Math.Max(port.Minimum, Math.Min(port.Maximum, configuredPort));
                antigravityConfigPath.Text = JsonValue.String(config, "antigravityConfigPath");
                antigravityConversationTitle.Text = JsonValue.String(config, "antigravityConversationTitle");
                freeChatEnabled.Checked = JsonValue.Boolean(config, "freeChatEnabled", true);
                bool antigravity = JsonValue.String(config, "chatTarget") == "antigravity-mcp";
                antigravityProvider.Checked = antigravity;
                activeProvider.Checked = !antigravity;
                string configuredActionMode = JsonValue.String(config, "actionMode");
                smartAiEnabled.Checked = configuredActionMode == "smart" || configuredActionMode == "hybrid";
                int configuredTokenBudget = JsonValue.Integer(config, "tokenBudget", 512);
                tokenBudget.Value = Math.Max(tokenBudget.Minimum, Math.Min(tokenBudget.Maximum, configuredTokenBudget));
                UpdateActionModeHint();

                Dictionary<string, object> persona = JsonValue.Object(config, "persona");
                bool custom = JsonValue.String(persona, "mode") == "custom";
                customPersona.Checked = custom;
                inheritPersona.Checked = !custom;
                personaDisplayName.Text = JsonValue.String(persona, "displayName");
                personaPersonality.Text = JsonValue.String(persona, "personality");
                personaSpeakingStyle.Text = JsonValue.String(persona, "speakingStyle");
                personaMemoryNotes.Text = JsonValue.String(persona, "memoryNotes");
                npcSkinMode = JsonValue.String(config, "npcSkinMode") == "custom" ? "custom" : "default";
                targetTouched = !String.IsNullOrWhiteSpace(targetVersion.Text);
                UpdatePersonaLayout();
                UpdateSkinStatus();
            }
            finally
            {
                loading = false;
            }
        }

        private async Task SaveAsync()
        {
            if (busy) return;
            SetBusy(true, "正在保存配置...");
            try
            {
                await SaveCoreAsync();
                ShowFooter("配置已保存");
            }
            catch (Exception error)
            {
                ShowError(error);
            }
            finally
            {
                SetBusy(false, "就绪");
            }
        }

        private async Task SaveCoreAsync()
        {
            ValidateForm();
            Dictionary<string, object> request = new Dictionary<string, object>();
            request["config"] = ReadConfig();
            Dictionary<string, object> result = await Task.Run(delegate { return api.Post("/api/save", request); });
            Dictionary<string, object> saved = JsonValue.Object(result, "config");
            FillInstances(JsonValue.Array(result, "instances"), JsonValue.String(saved, "sourceVersion"));
            saveStatus.Text = "已保存";
        }

        private Dictionary<string, object> ReadConfig()
        {
            Dictionary<string, object> persona = new Dictionary<string, object>();
            persona["mode"] = customPersona.Checked ? "custom" : "inherit";
            persona["displayName"] = personaDisplayName.Text;
            persona["personality"] = personaPersonality.Text;
            persona["speakingStyle"] = personaSpeakingStyle.Text;
            persona["memoryNotes"] = personaMemoryNotes.Text;

            Dictionary<string, object> config = new Dictionary<string, object>();
            config["launcherPath"] = launcherPath.Text;
            config["launcherArguments"] = launcherArguments.Text;
            config["minecraftRoot"] = minecraftRoot.Text;
            config["sourceVersion"] = sourceVersion.SelectedItem == null ? String.Empty : sourceVersion.SelectedItem.ToString();
            config["targetVersion"] = targetVersion.Text;
            config["playerName"] = playerName.Text;
            config["companionName"] = companionName.Text;
            config["port"] = Decimal.ToInt32(port.Value);
            config["freeChatEnabled"] = freeChatEnabled.Checked;
            config["chatTarget"] = antigravityProvider.Checked ? "antigravity-mcp" : "active-provider";
            config["actionMode"] = smartAiEnabled.Checked ? "smart" : "stable";
            config["tokenBudget"] = Decimal.ToInt32(tokenBudget.Value);
            config["persona"] = persona;
            config["npcSkinMode"] = npcSkinMode;
            config["antigravityConfigPath"] = antigravityConfigPath.Text;
            config["antigravityConversationTitle"] = antigravityConversationTitle.Text;
            return config;
        }

        private void ValidateForm()
        {
            if (String.IsNullOrWhiteSpace(launcherPath.Text)) throw new InvalidOperationException("请选择 HMCL 启动器。");
            if (String.IsNullOrWhiteSpace(minecraftRoot.Text)) throw new InvalidOperationException("请选择 Minecraft 根目录。");
            if (sourceVersion.SelectedItem == null) throw new InvalidOperationException("请选择源实例。");
            if (String.IsNullOrWhiteSpace(targetVersion.Text)) throw new InvalidOperationException("请填写 Codex 实例名。");
            if (String.IsNullOrWhiteSpace(playerName.Text)) throw new InvalidOperationException("请填写游戏玩家名。");
            if (String.IsNullOrWhiteSpace(companionName.Text)) throw new InvalidOperationException("请填写 NPC 名称。");
            if (String.IsNullOrWhiteSpace(antigravityConfigPath.Text)) throw new InvalidOperationException("请填写反重力 MCP 配置路径。");
            if (String.IsNullOrWhiteSpace(antigravityConversationTitle.Text)) throw new InvalidOperationException("请填写反重力会话标题。");
            if (npcSkinMode == "custom" && !customSkinAvailable) throw new InvalidOperationException("自定义 NPC 皮肤文件不存在。");
        }

        private async Task RunActionAsync(string title, string endpoint, bool saveFirst, bool mcp)
        {
            if (busy) return;
            SetBusy(true, title + "...");
            try
            {
                if (saveFirst) await SaveCoreAsync();
                await Task.Run(delegate { api.Post(endpoint, new Dictionary<string, object>()); });
                if (mcp) mcpStatus.Text = "测试通过";
                ShowFooter(title + "完成");
            }
            catch (Exception error)
            {
                if (mcp) mcpStatus.Text = "测试失败";
                ShowError(error);
            }
            finally
            {
                SetBusy(false, "就绪");
            }
            await RefreshAsync(false);
        }

        private void BrowseLauncher()
        {
            using (OpenFileDialog dialog = new OpenFileDialog())
            {
                dialog.Title = "选择 HMCL 启动器（.exe 或 .jar）";
                dialog.Filter = "HMCL 启动器 (*.exe;*.jar)|*.exe;*.jar|所有文件 (*.*)|*.*";
                dialog.CheckFileExists = true;
                dialog.RestoreDirectory = true;
                string current = launcherPath.Text.Trim();
                if (File.Exists(current))
                {
                    dialog.InitialDirectory = Path.GetDirectoryName(current);
                    dialog.FileName = Path.GetFileName(current);
                }
                else
                {
                    string desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
                    if (Directory.Exists(desktop)) dialog.InitialDirectory = desktop;
                }
                if (dialog.ShowDialog(this) == DialogResult.OK)
                {
                    launcherPath.Text = dialog.FileName;
                    MarkDirty();
                }
            }
        }

        private async Task BrowseMinecraftRootAsync()
        {
            using (FolderBrowserDialog dialog = new FolderBrowserDialog())
            {
                dialog.Description = "选择 Minecraft 根目录";
                dialog.ShowNewFolderButton = false;
                if (Directory.Exists(minecraftRoot.Text)) dialog.SelectedPath = minecraftRoot.Text;
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                minecraftRoot.Text = dialog.SelectedPath;
                MarkDirty();
            }
            await LoadInstancesAsync();
        }

        private async Task LoadInstancesAsync()
        {
            string selected = sourceVersion.SelectedItem == null ? String.Empty : sourceVersion.SelectedItem.ToString();
            Dictionary<string, object> body = new Dictionary<string, object>();
            body["minecraftRoot"] = minecraftRoot.Text;
            try
            {
                Dictionary<string, object> result = await Task.Run(delegate { return api.Post("/api/instances", body); });
                FillInstances(JsonValue.Array(result, "instances"), selected);
            }
            catch (Exception error)
            {
                ShowError(error);
            }
        }

        private async Task ChooseSkinAsync()
        {
            using (OpenFileDialog dialog = new OpenFileDialog())
            {
                dialog.Title = "选择 128x64 NPC 皮肤";
                dialog.Filter = "PNG skin (*.png)|*.png";
                dialog.CheckFileExists = true;
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                SetBusy(true, "正在导入 NPC 皮肤...");
                try
                {
                    Dictionary<string, object> body = new Dictionary<string, object>();
                    body["path"] = dialog.FileName;
                    await Task.Run(delegate { api.Post("/api/skin/import", body); });
                    npcSkinMode = "custom";
                    customSkinAvailable = true;
                    await LoadSkinPreviewAsync(true);
                    MarkDirty();
                    ShowFooter("皮肤已导入，保存配置后生效");
                }
                catch (Exception error)
                {
                    ShowError(error);
                }
                finally
                {
                    SetBusy(false, "就绪");
                }
            }
        }

        private async Task RestoreDefaultSkinAsync()
        {
            npcSkinMode = "default";
            await LoadSkinPreviewAsync(false);
            MarkDirty();
            ShowFooter("已切换默认皮肤，保存配置后生效");
        }

        private void BeginSkinPreviewLoad(bool custom)
        {
            Task ignored = LoadSkinPreviewAsync(custom);
        }

        private async Task LoadSkinPreviewAsync(bool custom)
        {
            try
            {
                string path = custom ? "/api/skin-preview" : "/assets/companion.png";
                byte[] bytes = await Task.Run(delegate { return api.GetBytes(path); });
                Image image;
                using (MemoryStream stream = new MemoryStream(bytes))
                using (Image source = Image.FromStream(stream))
                {
                    image = new Bitmap(source);
                }
                Image old = skinPreview.Image;
                skinPreview.Image = image;
                if (old != null) old.Dispose();
            }
            catch (Exception error)
            {
                ShowFooter("皮肤预览失败：" + error.Message);
            }
            UpdateSkinStatus();
        }

        private void FillInstances(object[] instances, string selected)
        {
            loading = true;
            try
            {
                sourceVersion.Items.Clear();
                foreach (object item in instances)
                {
                    Dictionary<string, object> instance = item as Dictionary<string, object>;
                    if (instance == null || JsonValue.Boolean(instance, "isCompanionClone", false)) continue;
                    string name = JsonValue.String(instance, "name");
                    if (!String.IsNullOrWhiteSpace(name)) sourceVersion.Items.Add(name);
                }
                if (!String.IsNullOrWhiteSpace(selected) && sourceVersion.Items.Contains(selected)) sourceVersion.SelectedItem = selected;
                else sourceVersion.SelectedIndex = -1;
            }
            finally
            {
                loading = false;
            }
        }

        private void UpdateService(
            Dictionary<string, object> service,
            Dictionary<string, object> readiness
        )
        {
            bool running = JsonValue.Boolean(service, "running", false);
            bool serviceVerified = JsonValue.Boolean(readiness, "serviceVerified", false);
            bool minecraftConnected = JsonValue.Boolean(readiness, "minecraftConnected", false);
            bool antigravityBound = JsonValue.Boolean(readiness, "antigravityBound", false);
            bool tRoundTripVerified = JsonValue.Boolean(readiness, "tRoundTripVerified", false);
            string error = JsonValue.String(service, "error");
            bool complete = serviceVerified && minecraftConnected && antigravityBound && tRoundTripVerified;
            serviceStatus.Running = complete;
            serviceStatus.StatusText = running
                ? "服务" + StatusMark(serviceVerified)
                    + " MC" + StatusMark(minecraftConnected)
                    + " 反重力" + StatusMark(antigravityBound)
                    + " T" + (tRoundTripVerified ? "✓" : "待验")
                : String.IsNullOrWhiteSpace(error) ? "服务未启动" : "服务身份未通过";

            Dictionary<string, object> bridge = JsonValue.Object(readiness, "minecraftBridge");
            string requiredVersion = JsonValue.String(bridge, "requiredVersion");
            List<string> reportedVersions = new List<string>();
            foreach (object value in JsonValue.Array(bridge, "reportedVersions"))
            {
                string version = Convert.ToString(value);
                if (!String.IsNullOrWhiteSpace(version)) reportedVersions.Add(version);
            }
            string versionDetail = reportedVersions.Count == 0
                ? "未检测到模组版本"
                : "已连接模组 " + String.Join(", ", reportedVersions.ToArray());
            hints.SetToolTip(
                serviceStatus,
                "服务身份：" + StateText(serviceVerified)
                    + "\r\nMinecraft：" + StateText(minecraftConnected)
                    + (String.IsNullOrWhiteSpace(requiredVersion) ? String.Empty : "（需要 " + requiredVersion + "；" + versionDetail + "）")
                    + "\r\n反重力会话：" + StateText(antigravityBound)
                    + "\r\n本次进程 T 往返：" + StateText(tRoundTripVerified)
            );
            connectionAddress.Text = "控制服务：http://127.0.0.1:" + port.Value + "/";
        }

        private static string StatusMark(bool value)
        {
            return value ? "✓" : "×";
        }

        private static string StateText(bool value)
        {
            return value ? "已验证" : "未验证";
        }

        private void RenderEvents(object[] events)
        {
            eventsList.BeginUpdate();
            try
            {
                eventsList.Items.Clear();
                for (int index = events.Length - 1; index >= 0; index--)
                {
                    Dictionary<string, object> entry = events[index] as Dictionary<string, object>;
                    if (entry == null) continue;
                    DateTime at;
                    string atText = DateTime.TryParse(JsonValue.String(entry, "at"), out at) ? at.ToLocalTime().ToString("HH:mm:ss") : "--:--:--";
                    ListViewItem row = new ListViewItem(atText);
                    row.SubItems.Add(JsonValue.String(entry, "level"));
                    row.SubItems.Add(JsonValue.String(entry, "message"));
                    eventsList.Items.Add(row);
                }
            }
            finally
            {
                eventsList.EndUpdate();
            }
        }

        private void UpdatePersonaLayout()
        {
            if (personaFields == null || personaGroup == null) return;
            bool custom = customPersona.Checked;
            personaFields.Visible = custom;
            personaGroup.Height = custom ? 330 : 92;
            ResizeConfigGroups();
            if (!loading) MarkDirty();
        }

        private void UpdateActionModeHint()
        {
            if (actionModeHint == null || smartAiEnabled == null) return;
            if (tokenBudget != null) tokenBudget.Enabled = smartAiEnabled.Checked;
            actionModeHint.Text = smartAiEnabled.Checked
                ? "AI 只生成一个结构化任务；Claude 为硬上限，Codex 与反重力为软预算。"
                : "动作只走本地规则；自由聊天由独立开关控制。";
        }

        private void UpdateSkinStatus()
        {
            if (skinStatus == null) return;
            skinStatus.Text = npcSkinMode == "custom"
                ? customSkinAvailable ? "自定义皮肤，重启游戏后生效" : "自定义皮肤文件缺失"
                : "当前白发猫娘皮肤";
        }

        private void ResizeConfigGroups()
        {
            if (configFlow == null) return;
            int width = Math.Max(860, configFlow.ClientSize.Width - 54);
            foreach (Control control in configFlow.Controls) control.Width = width;
            configFlow.PerformLayout();
        }

        private void MarkDirty()
        {
            if (loading) return;
            if (saveStatus != null) saveStatus.Text = "有未保存修改";
        }

        private void SetBusy(bool value, string message)
        {
            busy = value;
            operationStatus.Text = message;
            footerMode.Text = value ? "处理中" : "原生客户端";
            foreach (Control control in actionControls) control.Enabled = !value;
            UseWaitCursor = value;
        }

        private void ShowFooter(string message)
        {
            footerMessage.Text = message;
        }

        private void ShowError(Exception error)
        {
            string message = error == null ? "未知错误" : error.Message;
            ShowFooter("错误：" + message);
            MessageBox.Show(this, message, "Minecraft Codex Companion", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }

        private void CopyPrompt()
        {
            if (String.IsNullOrWhiteSpace(promptBox.Text)) return;
            Clipboard.SetText(promptBox.Text);
            ShowFooter("陪玩提示词已复制");
        }

        private Button ActionButton(string text, string endpoint, bool saveFirst, bool primary)
        {
            Button button = MakeButton(text, primary);
            int measured = TextRenderer.MeasureText(text, button.Font).Width + 28;
            button.Size = new Size(Math.Max(112, Math.Min(190, measured)), 32);
            button.Click += async delegate { await RunActionAsync(text, endpoint, saveFirst, endpoint == "/api/mcp/test"); };
            actionControls.Add(button);
            return button;
        }

        private Button MakeButton(string text, bool primary)
        {
            Button button = new Button();
            button.Text = text;
            button.AutoSize = false;
            button.Size = new Size(92, 30);
            button.FlatStyle = FlatStyle.Flat;
            button.FlatAppearance.BorderSize = 1;
            button.FlatAppearance.BorderColor = primary ? Forest : Border;
            button.FlatAppearance.MouseOverBackColor = primary ? Color.FromArgb(46, 122, 84) : Color.FromArgb(237, 244, 240);
            button.FlatAppearance.MouseDownBackColor = primary ? Color.FromArgb(30, 87, 59) : Color.FromArgb(224, 235, 229);
            button.BackColor = primary ? Forest : Color.White;
            button.ForeColor = primary ? Color.White : ForestDark;
            button.Font = new Font("Microsoft YaHei UI", 9F, primary ? FontStyle.Bold : FontStyle.Regular);
            button.AutoEllipsis = true;
            button.Cursor = Cursors.Hand;
            return button;
        }

        private GroupBox MakeGroup(string text)
        {
            GroupBox group = new FlatGroupBox();
            group.Text = text;
            group.BackColor = Color.White;
            group.Padding = new Padding(14, 23, 14, 11);
            group.Margin = new Padding(4, 3, 4, 6);
            return group;
        }

        private TableLayoutPanel MakeGrid(int columns, int rows)
        {
            TableLayoutPanel grid = new TableLayoutPanel();
            grid.Dock = DockStyle.Fill;
            grid.BackColor = Color.White;
            grid.Padding = new Padding(10, 4, 10, 4);
            grid.ColumnCount = columns;
            grid.RowCount = rows;
            return grid;
        }

        private TextBox MakeTextBox()
        {
            TextBox box = new TextBox();
            box.Dock = DockStyle.Fill;
            box.BorderStyle = BorderStyle.FixedSingle;
            box.BackColor = Color.White;
            box.Margin = new Padding(4, 5, 8, 4);
            box.TextChanged += delegate { MarkDirty(); };
            return box;
        }

        private TextBox MakeMultilineTextBox()
        {
            TextBox box = MakeTextBox();
            box.Multiline = true;
            box.ScrollBars = ScrollBars.Vertical;
            return box;
        }

        private Label MakeLabel(string text, bool strong)
        {
            Label label = new Label();
            label.Text = text;
            label.AutoSize = true;
            label.ForeColor = ForestDark;
            label.Font = new Font("Microsoft YaHei UI", strong ? 9.25F : 9F, strong ? FontStyle.Bold : FontStyle.Regular);
            return label;
        }

        private void AddWidePathRow(TableLayoutPanel grid, int row, string labelText, TextBox box, Button browse)
        {
            AddGridLabel(grid, labelText, 0, row);
            TableLayoutPanel path = new TableLayoutPanel();
            path.Dock = DockStyle.Fill;
            path.ColumnCount = 2;
            path.RowCount = 1;
            path.Margin = new Padding(0);
            path.Padding = new Padding(0, 0, 4, 0);
            path.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100F));
            path.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 104F));
            path.RowStyles.Add(new RowStyle(SizeType.Percent, 100F));
            box.Margin = new Padding(0, 5, 10, 5);
            browse.Dock = DockStyle.Fill;
            browse.Margin = new Padding(0, 2, 0, 2);
            path.Controls.Add(box, 0, 0);
            path.Controls.Add(browse, 1, 0);
            grid.Controls.Add(path, 1, row);
            grid.SetColumnSpan(path, 3);
            actionControls.Add(browse);
        }

        private void AddWideRow(TableLayoutPanel grid, int row, string labelText, Control control)
        {
            AddGridLabel(grid, labelText, 0, row);
            grid.Controls.Add(control, 1, row);
            grid.SetColumnSpan(control, 3);
        }

        private void AddPairRow(TableLayoutPanel grid, int row, string leftLabel, Control left, string rightLabel, Control right)
        {
            AddGridLabel(grid, leftLabel, 0, row);
            grid.Controls.Add(left, 1, row);
            if (!String.IsNullOrEmpty(rightLabel)) AddGridLabel(grid, rightLabel, 2, row);
            grid.Controls.Add(right, 3, row);
        }

        private void AddField(TableLayoutPanel grid, int row, string labelText, Control control)
        {
            AddGridLabel(grid, labelText, 0, row);
            grid.Controls.Add(control, 1, row);
        }

        private void AddGridLabel(TableLayoutPanel grid, string text, int column, int row)
        {
            Label label = MakeLabel(text, false);
            label.Dock = DockStyle.Fill;
            label.TextAlign = ContentAlignment.MiddleLeft;
            label.Margin = new Padding(3, 3, 5, 3);
            grid.Controls.Add(label, column, row);
        }
    }

    internal static class ClientProgram
    {
        private const string ProductionMutexName = "Local\\MinecraftCodexCompanionClient";

        private static string ResolveMutexName()
        {
            string suffix = Environment.GetEnvironmentVariable("MC_COMPANION_CLIENT_TEST_MUTEX_SUFFIX");
            if (String.IsNullOrWhiteSpace(suffix) || suffix.Length > 64) return ProductionMutexName;

            string executable = Path.GetFullPath(Application.ExecutablePath);
            string temporaryRoot = Path.GetFullPath(Path.GetTempPath()).TrimEnd(
                Path.DirectorySeparatorChar,
                Path.AltDirectorySeparatorChar
            ) + Path.DirectorySeparatorChar;
            if (!executable.StartsWith(temporaryRoot, StringComparison.OrdinalIgnoreCase)) return ProductionMutexName;

            foreach (char character in suffix)
            {
                if (!Char.IsLetterOrDigit(character) && character != '-' && character != '_')
                {
                    return ProductionMutexName;
                }
            }
            return ProductionMutexName + "-test-" + suffix;
        }

        [STAThread]
        private static int Main(string[] args)
        {
            if (args.Length >= 1 && args[0] == "self-test")
            {
                if (args.Length >= 2)
                {
                    File.WriteAllText(args[1], "ok", new UTF8Encoding(false));
                }
                return 0;
            }

            if (args.Length >= 1 && args[0] == "layout-self-test")
            {
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                bool header = true;
                foreach (float scale in new float[] { 1F, 1.25F, 1.5F, 2F })
                {
                    using (CompanionClientForm form = new CompanionClientForm("http://127.0.0.1:1", "layout-self-test"))
                    {
                        header = header && form.ValidateHeaderLayoutForTest(
                            new Size((int)(980 * scale), (int)(700 * scale)),
                            scale
                        );
                    }
                }
                using (CompanionClientForm form = new CompanionClientForm("http://127.0.0.1:1", "layout-self-test"))
                {
                    bool minimum = form.ValidateAntigravityLayoutForTest(new Size(980, 700));
                    bool normal = form.ValidateAntigravityLayoutForTest(new Size(1160, 820));
                    if (args.Length >= 2)
                    {
                        File.WriteAllText(args[1], header && minimum && normal ? "ok" : "failed", new UTF8Encoding(false));
                    }
                    return header && minimum && normal ? 0 : 3;
                }
            }

            string endpoint = Environment.GetEnvironmentVariable("MC_COMPANION_CLIENT_ENDPOINT");
            string session = Environment.GetEnvironmentVariable("MC_COMPANION_CLIENT_SESSION");
            if (String.IsNullOrWhiteSpace(endpoint) || String.IsNullOrWhiteSpace(session))
            {
                MessageBox.Show("请通过 MinecraftCodexCompanion.exe 启动独立客户端。", "Minecraft Codex Companion", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 2;
            }

            bool created;
            using (System.Threading.Mutex mutex = new System.Threading.Mutex(true, ResolveMutexName(), out created))
            {
                if (!created)
                {
                    MessageBox.Show("Minecraft Codex Companion 独立客户端已经在运行。", "Minecraft Codex Companion", MessageBoxButtons.OK, MessageBoxIcon.Information);
                    return 0;
                }

                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                try
                {
                    Application.Run(new CompanionClientForm(endpoint, session));
                    return 0;
                }
                catch (Exception error)
                {
                    MessageBox.Show(error.Message, "Minecraft Codex Companion", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    return 1;
                }
            }
        }
    }
}
