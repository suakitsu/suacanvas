using PicPilot.Core;
using PicPilot.App.Services;
using PicPilot.App.ViewModels;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Windows.Threading;

const string baseUrl = "https://suakitsu.com";
var email = Environment.GetEnvironmentVariable("PICPILOT_TEST_EMAIL");
var password = Environment.GetEnvironmentVariable("PICPILOT_TEST_PASSWORD");

if (string.IsNullOrWhiteSpace(email) || string.IsNullOrWhiteSpace(password))
{
    Console.Error.WriteLine("PROBE_CONFIG_MISSING");
    return 2;
}

using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(3));
var tokenStore = new MemoryTokenStore();
var httpHandler = new HttpClientHandler
{
#pragma warning disable SYSLIB0014
    Proxy = WebRequest.GetSystemWebProxy(),
#pragma warning restore SYSLIB0014
    UseProxy = true,
    AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate | DecompressionMethods.Brotli
};
using var api = new PicPilotApiClient(new Uri(baseUrl), tokenStore, httpHandler);
var failures = 0;

try
{
    var auth = await api.LoginAsync(email, password, rememberMe: false, ct: timeout.Token);
    Console.WriteLine($"token-bytes={Encoding.UTF8.GetByteCount(auth.AccessToken)}");
    var credentialProbe = new WindowsCredentialTokenStore("PicPilot.Windows.ReadOnlyProbe");
    await credentialProbe.SaveAsync(auth.AccessToken, timeout.Token);
    var storedToken = await credentialProbe.ReadAsync(timeout.Token);
    Console.WriteLine($"credential-store={(storedToken == auth.AccessToken ? "ok" : "mismatch")}");
    await credentialProbe.ClearAsync(timeout.Token);
    await tokenStore.SaveAsync(auth.AccessToken, timeout.Token);
    Console.WriteLine("login=ok");
}
catch (ApiException exception)
{
    Console.Error.WriteLine($"login=failed status={exception.StatusCode} code={SafeCode(exception.Code)}");
    return 3;
}
catch (Exception exception)
{
    Console.Error.WriteLine($"login=failed type={exception.GetType().Name}");
    return 3;
}

await ProbeAsync("me", () => api.GetMeAsync(timeout.Token), _ => "available");
await ProbeAsync("billing-summary", () => api.GetBillingSummaryAsync(timeout.Token), value => $"credits-nonnegative={value.Credits >= 0}");
await ProbeAsync("projects", () => api.GetProjectsAsync(timeout.Token), value => $"count={value.Count}");
await ProbeAsync("creative-chats", () => api.GetCreativeChatsAsync(timeout.Token), value => $"count={value.Count}");
await ProbeAsync("video-tasks", () => api.GetVideoTasksAsync(timeout.Token), value => $"count={value.Count}");
await ProbeAsync("video-config", () => api.GetVideoConfigAsync(timeout.Token), IsObject);
await ProbeAsync("commerce-catalog", () => api.GetCommerceCatalogAsync(timeout.Token), _ => "available");
await ProbeAsync("translation-languages", () => api.GetTranslationLanguagesAsync(timeout.Token), value => $"count={value.Count}");
await ProbeAsync("recharge-plans", () => api.GetRechargePlansAsync(timeout.Token), value => $"count={value.Count}");
await ProbeAsync("payment-channels", () => api.GetPaymentChannelsAsync(timeout.Token), value => $"count={value.Count}");
await ProbeAsync("messages", () => api.GetMessagesAsync(timeout.Token), value => $"count={value.Count}");
await ProbeAsync("credit-ledger", () => api.GetCreditLedgerAsync(1, 5, timeout.Token), value => $"count={value.Rows.Count}");
await ProbeAsync("recharge-orders", () => api.GetRechargeOrdersAsync(1, 5, "", timeout.Token), value => $"count={value.Rows.Count}");

await tokenStore.ClearAsync(timeout.Token);

try
{
    var flowResult = await RunViewModelFlowAsync(baseUrl, email, password, timeout.Token);
    Console.WriteLine($"view-model-login={(flowResult.Authenticated ? "ok" : "failed")} error-present={flowResult.ErrorPresent}");
    if (!flowResult.Authenticated || flowResult.ErrorPresent) failures++;
}
catch (Exception exception)
{
    failures++;
    Console.WriteLine($"view-model-login=failed type={exception.GetType().Name}");
}

Console.WriteLine($"summary={(failures == 0 ? "ok" : "failed")} failures={failures} paid-requests=0");
return failures == 0 ? 0 : 4;

async Task ProbeAsync<T>(string name, Func<Task<T>> request, Func<T, string> describe)
{
    try
    {
        var value = await request();
        Console.WriteLine($"{name}=ok {describe(value)}");
    }
    catch (ApiException exception)
    {
        failures++;
        Console.WriteLine($"{name}=failed status={exception.StatusCode} code={SafeCode(exception.Code)}");
    }
    catch (Exception exception)
    {
        failures++;
        Console.WriteLine($"{name}=failed type={exception.GetType().Name}");
    }
}

static string IsObject(JsonElement value) => $"json={value.ValueKind.ToString().ToLowerInvariant()}";

static Task<(bool Authenticated, bool ErrorPresent)> RunViewModelFlowAsync(
    string baseUrl,
    string email,
    string password,
    CancellationToken cancellationToken)
{
    var completion = new TaskCompletionSource<(bool, bool)>(TaskCreationOptions.RunContinuationsAsynchronously);
    var thread = new Thread(() =>
    {
        var dispatcher = Dispatcher.CurrentDispatcher;
        SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(dispatcher));
        dispatcher.BeginInvoke(async () =>
        {
            try
            {
                var flowStore = new WindowsCredentialTokenStore("PicPilot.Windows.ReadOnlyFlowProbe");
                using var flowHandler = new HttpClientHandler
                {
#pragma warning disable SYSLIB0014
                    Proxy = WebRequest.GetSystemWebProxy(),
#pragma warning restore SYSLIB0014
                    UseProxy = true,
                    AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate | DecompressionMethods.Brotli
                };
                using var flowApi = new PicPilotApiClient(new Uri(baseUrl), flowStore, flowHandler);
                var flowSession = new SessionService(flowStore);
                var viewModel = new MainViewModel(flowApi, flowSession)
                {
                    Email = email,
                    Password = password,
                    RememberMe = true
                };

                await viewModel.SubmitAuthCommand.ExecuteAsync(null);
                var result = (viewModel.IsAuthenticated, !string.IsNullOrWhiteSpace(viewModel.ErrorMessage));
                await flowSession.SignOutAsync(cancellationToken);
                completion.TrySetResult(result);
            }
            catch (Exception exception)
            {
                completion.TrySetException(exception);
            }
            finally
            {
                dispatcher.BeginInvokeShutdown(DispatcherPriority.Background);
            }
        });
        Dispatcher.Run();
    })
    {
        IsBackground = true,
        Name = "PicPilot.ReadOnlyProbe.STA"
    };
    thread.SetApartmentState(ApartmentState.STA);
    thread.Start();
    return completion.Task.WaitAsync(cancellationToken);
}

static string SafeCode(string? value)
{
    var code = new string((value ?? "UNKNOWN").Where(character => char.IsAsciiLetterOrDigit(character) || character is '_' or '-').Take(64).ToArray());
    return string.IsNullOrWhiteSpace(code) ? "UNKNOWN" : code;
}
