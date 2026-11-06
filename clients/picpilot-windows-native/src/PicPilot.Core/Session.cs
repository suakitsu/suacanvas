namespace PicPilot.Core;

public interface ITokenStore
{
    Task<string> ReadAsync(CancellationToken cancellationToken = default);
    Task SaveAsync(string token, CancellationToken cancellationToken = default);
    Task ClearAsync(CancellationToken cancellationToken = default);
}

public sealed class MemoryTokenStore : ITokenStore
{
    private string _token = "";
    public Task<string> ReadAsync(CancellationToken cancellationToken = default) => Task.FromResult(_token);
    public Task SaveAsync(string token, CancellationToken cancellationToken = default) { _token = token; return Task.CompletedTask; }
    public Task ClearAsync(CancellationToken cancellationToken = default) { _token = ""; return Task.CompletedTask; }
}

public sealed class SessionService
{
    private readonly ITokenStore _tokenStore;

    public SessionService(ITokenStore tokenStore) => _tokenStore = tokenStore;

    public UserProfile? CurrentUser { get; private set; }
    public bool IsAuthenticated => CurrentUser is not null;
    public event EventHandler? Changed;

    public async Task SetAsync(AuthResponse auth, CancellationToken cancellationToken = default)
    {
        await _tokenStore.SaveAsync(auth.AccessToken, cancellationToken);
        CurrentUser = auth.User;
        Changed?.Invoke(this, EventArgs.Empty);
    }

    public void SetProfile(UserProfile profile)
    {
        CurrentUser = profile;
        Changed?.Invoke(this, EventArgs.Empty);
    }

    public async Task SignOutAsync(CancellationToken cancellationToken = default)
    {
        await _tokenStore.ClearAsync(cancellationToken);
        CurrentUser = null;
        Changed?.Invoke(this, EventArgs.Empty);
    }
}
