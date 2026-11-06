using PicPilot.App.Services;

namespace PicPilot.Tests;

public sealed class NavigationUriPolicyTests
{
    [Fact]
    public void Assistant_relative_action_is_resolved_to_the_current_picpilot_environment()
    {
        var success = NavigationUriPolicy.TryResolve(
            "https://suakitsu.com",
            "/commerce?tab=suite",
            out var uri);

        Assert.True(success);
        Assert.Equal("https://suakitsu.com/commerce?tab=suite", uri.AbsoluteUri);
    }

    [Theory]
    [InlineData("javascript:alert(1)")]
    [InlineData("file:///C:/private.txt")]
    [InlineData("//untrusted.example/path")]
    [InlineData("commerce?tab=suite")]
    [InlineData("")]
    public void Unsafe_or_ambiguous_actions_are_rejected(string requestedUrl)
    {
        Assert.False(NavigationUriPolicy.TryResolve("https://suakitsu.com", requestedUrl, out _));
    }

    [Fact]
    public void Absolute_https_payment_page_remains_supported()
    {
        var success = NavigationUriPolicy.TryResolve(
            "https://suakitsu.com",
            "https://pay.example.com/order/fixture",
            out var uri);

        Assert.True(success);
        Assert.Equal("pay.example.com", uri.Host);
    }
}
