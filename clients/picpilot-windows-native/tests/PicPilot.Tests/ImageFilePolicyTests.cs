using PicPilot.Core;

namespace PicPilot.Tests;

public sealed class ImageFilePolicyTests : IDisposable
{
    private readonly string _folder = Path.Combine(Path.GetTempPath(), "PicPilotTests", Guid.NewGuid().ToString("N"));

    public ImageFilePolicyTests() => Directory.CreateDirectory(_folder);

    [Theory]
    [InlineData("sample.jpg")]
    [InlineData("sample.JPEG")]
    [InlineData("sample.png")]
    [InlineData("sample.webp")]
    [InlineData("sample.HEIC")]
    [InlineData("sample.heif")]
    public void Supported_extensions_are_accepted_case_insensitively(string name)
    {
        var path = CreateFile(name, 32);
        Assert.Null(ImageFilePolicy.Validate([path]));
    }

    [Fact]
    public void Unsupported_extension_is_rejected_with_clear_message()
    {
        var path = CreateFile("not-an-image.txt", 32);
        var error = ImageFilePolicy.Validate([path]);
        Assert.Contains("不支持", error);
        Assert.Contains("not-an-image.txt", error);
    }

    [Fact]
    public void Empty_file_is_rejected()
    {
        var path = CreateFile("empty.png", 0);
        Assert.Contains("为空或已损坏", ImageFilePolicy.Validate([path]));
    }

    [Fact]
    public void File_count_and_total_size_are_enforced()
    {
        var one = CreateFile("one.jpg", 16);
        var two = CreateFile("two.png", 16);
        Assert.Contains("最多选择 1 张", ImageFilePolicy.Validate([one, two], maxFiles: 1));
        Assert.Contains("合计不能超过", ImageFilePolicy.Validate([one, two], maxTotalBytes: 24));
    }

    [Fact]
    public void Single_file_size_is_enforced()
    {
        var path = CreateFile("large.webp", 33);
        Assert.Contains("单张图片不能超过", ImageFilePolicy.Validate([path], maxSingleBytes: 32, maxTotalBytes: 100));
    }

    [Theory]
    [InlineData("../unsafe:name?.png", "unsafe_name_.png")]
    [InlineData("", "PicPilot-output")]
    public void Download_name_is_reduced_to_a_safe_file_name(string input, string expected)
    {
        Assert.Equal(expected, ImageFilePolicy.SanitizeDownloadName(input));
    }

    private string CreateFile(string name, int bytes)
    {
        var path = Path.Combine(_folder, name);
        File.WriteAllBytes(path, new byte[bytes]);
        return path;
    }

    public void Dispose()
    {
        try { Directory.Delete(_folder, recursive: true); } catch { }
    }
}
