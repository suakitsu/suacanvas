using PicPilot.App.Services;

namespace PicPilot.Tests;

public sealed class WindowsCredentialTokenStoreTests
{
    [Fact]
    public async Task Token_lifecycle_uses_an_isolated_windows_credential()
    {
        var store = new WindowsCredentialTokenStore($"PicPilot.Windows.Tests.{Guid.NewGuid():N}");
        try
        {
            Assert.Equal("", await store.ReadAsync());
            await store.SaveAsync("fixture-token-that-is-not-a-production-secret");
            Assert.Equal("fixture-token-that-is-not-a-production-secret", await store.ReadAsync());
            await store.ClearAsync();
            Assert.Equal("", await store.ReadAsync());
        }
        finally
        {
            await store.ClearAsync();
        }
    }
}
