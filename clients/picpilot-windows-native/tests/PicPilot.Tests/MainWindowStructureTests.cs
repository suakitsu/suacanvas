using System.Globalization;
using System.Xml.Linq;

namespace PicPilot.Tests;

public sealed class MainWindowStructureTests
{
    private static readonly XNamespace Presentation = "http://schemas.microsoft.com/winfx/2006/xaml/presentation";
    private static readonly XNamespace Xaml = "http://schemas.microsoft.com/winfx/2006/xaml";

    [Fact]
    public void Every_visible_button_has_a_command_or_click_handler()
    {
        var document = XDocument.Load(Fixture("MainWindow.xaml"));
        var inertButtons = document
            .Descendants(Presentation + "Button")
            .Where(button => button.Attribute("Command") is null
                && button.Attribute("Click") is null
                && button.Elements().All(element => element.Name.LocalName != "Button.Command"))
            .Select(DescribeButton)
            .ToArray();

        Assert.True(inertButtons.Length == 0,
            "These buttons have no Command or Click handler: " + string.Join(", ", inertButtons));
    }

    [Fact]
    public void Native_workflows_download_controls_and_assistant_are_present()
    {
        var xaml = File.ReadAllText(Fixture("MainWindow.xaml"));
        var requiredBindings = new[]
        {
            "IsListingWorkflow",
            "IsAplusWorkflow",
            "IsPlatformWorkflow",
            "IsTranslationWorkflow",
            "IsDomesticWorkflow",
            "GenerateListingPackageCommand",
            "GenerateAplusCommand",
            "GenerateCommerceCommand",
            "GenerateTranslationCommand",
            "GenerateDomesticCommand",
            "GenerateFreeCreativeCommand",
            "GenerateAiCommand",
            "SendAssistantCommand",
            "自由创作",
            "对话生图",
            "文案 Agent",
            "AutoDownloadImages",
            "MinimizeToTray",
            "ChooseDownloadFolder_Click",
            "SyncCompletedImages_Click",
            "ImagePreviewOverlay",
            "ImagePreviewZoom"
        };

        foreach (var required in requiredBindings)
            Assert.Contains(required, xaml, StringComparison.Ordinal);
    }

    [Fact]
    public void Image_preview_is_an_in_window_lightbox_and_tray_has_explicit_exit()
    {
        var xaml = File.ReadAllText(Fixture("MainWindow.xaml"));
        var codeBehind = File.ReadAllText(Fixture("MainWindow.xaml.cs.txt"));

        Assert.Contains("ImagePreviewOverlay", xaml, StringComparison.Ordinal);
        Assert.Contains("ShowImagePreview", codeBehind, StringComparison.Ordinal);
        Assert.DoesNotContain("new ImagePreviewWindow", codeBehind, StringComparison.Ordinal);
        Assert.Contains("NotifyIcon", codeBehind, StringComparison.Ordinal);
        Assert.Contains("退出 PicPilot", codeBehind, StringComparison.Ordinal);
    }

    [Fact]
    public void Window_keeps_a_supported_minimum_and_has_compact_layout_rules()
    {
        var document = XDocument.Load(Fixture("MainWindow.xaml"));
        var window = Assert.Single(document.Elements(Presentation + "Window"));
        var minWidth = double.Parse(window.Attribute("MinWidth")!.Value, CultureInfo.InvariantCulture);
        var minHeight = double.Parse(window.Attribute("MinHeight")!.Value, CultureInfo.InvariantCulture);
        var codeBehind = File.ReadAllText(Fixture("MainWindow.xaml.cs.txt"));

        Assert.InRange(minWidth, 800, 960);
        Assert.InRange(minHeight, 600, 720);
        Assert.Contains("ActualWidth < 1120", codeBehind, StringComparison.Ordinal);
        Assert.Contains("SidebarColumn.Width", codeBehind, StringComparison.Ordinal);
        Assert.Contains("AuthHeroColumn.Width", codeBehind, StringComparison.Ordinal);
        Assert.Contains("NavLabel", codeBehind, StringComparison.Ordinal);
    }

    [Fact]
    public void File_diagnostics_are_opt_in_bounded_and_redirectable()
    {
        var source = File.ReadAllText(Fixture("AppDataPaths.cs.txt"));

        Assert.Contains("PICPILOT_DATA_DIR", source, StringComparison.Ordinal);
        Assert.Contains("PICPILOT_LOG_DIR", source, StringComparison.Ordinal);
        Assert.Contains("DefaultMaxLogBytes", source, StringComparison.Ordinal);
        Assert.Contains("? null", source, StringComparison.Ordinal);
        Assert.DoesNotContain(@"C:\\", source, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("C:/", source, StringComparison.OrdinalIgnoreCase);
    }

    private static string Fixture(string fileName) =>
        Path.Combine(AppContext.BaseDirectory, "Fixtures", fileName);

    private static string DescribeButton(XElement button)
    {
        var content = button.Attribute("Content")?.Value;
        var name = button.Attribute(Xaml + "Name")?.Value;
        return content ?? name ?? "<unnamed button>";
    }
}
