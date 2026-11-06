using PicPilot.Core;
using System.Net;
using System.Text;
using System.Text.Json;

namespace PicPilot.Tests;

public sealed class PicPilotApiClientTests
{
    [Fact]
    public async Task Login_parses_snake_case_envelope()
    {
        var handler = new StubHandler((request, _) => Json(HttpStatusCode.OK, """
            {"success":true,"code":"OK","message":"ok","request_id":"req-1","data":{"access_token":"fixture-token","user":{"id":"u_test","email_masked":"t***@example.com","email_display":"test@example.com","nickname":"Tester","role":"user","credits":88,"ui_language":"zh-CN","theme":"system","avatar_url":""}}}
            """));
        using var client = new PicPilotApiClient(new Uri("https://example.com"), new MemoryTokenStore(), handler);

        var result = await client.LoginAsync("test@example.com", "fixture-password");

        Assert.Equal("fixture-token", result.AccessToken);
        Assert.Equal(88, result.User.Credits);
        Assert.Equal("test@example.com", result.User.DisplayEmail);
        Assert.Equal("/api/auth/login", handler.LastRequest!.RequestUri!.AbsolutePath);
    }

    [Fact]
    public async Task Authenticated_request_uses_bearer_token()
    {
        var store = new MemoryTokenStore();
        await store.SaveAsync("test-bearer-token");
        var handler = new StubHandler((request, _) => Json(HttpStatusCode.OK, """
            {"success":true,"code":"OK","message":"ok","request_id":"r","data":{"credits":50,"pending_orders":0}}
            """));
        using var client = new PicPilotApiClient(new Uri("https://example.com"), store, handler);

        await client.GetBillingSummaryAsync();

        Assert.Equal("Bearer", handler.LastRequest!.Headers.Authorization!.Scheme);
        Assert.Equal("test-bearer-token", handler.LastRequest.Headers.Authorization.Parameter);
    }

    [Fact]
    public async Task Server_error_is_safely_localized()
    {
        var store = new MemoryTokenStore();
        await store.SaveAsync("token");
        var handler = new StubHandler((_, _) => Json(HttpStatusCode.InternalServerError, """
            {"success":false,"code":"INTERNAL_ERROR","message":"stack trace must not surface","request_id":"req-safe","data":null}
            """));
        using var client = new PicPilotApiClient(new Uri("https://example.com"), store, handler);

        var error = await Assert.ThrowsAsync<ApiException>(() => client.GetProjectsAsync());

        Assert.Equal("服务暂时不可用，请稍后重试。", error.Message);
        Assert.Equal("req-safe", error.RequestId);
        Assert.DoesNotContain("stack", error.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Theory]
    [InlineData(HttpStatusCode.Unauthorized, "TOKEN_INVALID", "登录已失效，请重新登录。")]
    [InlineData((HttpStatusCode)429, "RATE_LIMITED", "操作过于频繁，请稍后再试。")]
    public async Task Authentication_and_rate_limit_errors_are_safely_localized(HttpStatusCode status, string code, string expected)
    {
        var store = new MemoryTokenStore();
        await store.SaveAsync("token");
        var handler = new StubHandler((_, _) => Json(status, $$"""
            {"success":false,"code":"{{code}}","message":"unsafe upstream details","request_id":"req-safe","data":null}
            """));
        using var client = new PicPilotApiClient(new Uri("https://example.com"), store, handler);

        var error = await Assert.ThrowsAsync<ApiException>(() => client.GetProjectsAsync());

        Assert.Equal(expected, error.Message);
        Assert.Equal((int)status, error.StatusCode);
    }

    [Fact]
    public async Task Authenticated_request_without_token_is_rejected_before_network_call()
    {
        var calls = 0;
        var handler = new StubHandler((_, _) => { calls++; return Json(HttpStatusCode.OK, "{}"); });
        using var client = new PicPilotApiClient(new Uri("https://example.com"), new MemoryTokenStore(), handler);

        var error = await Assert.ThrowsAsync<ApiException>(() => client.GetProjectsAsync());

        Assert.Equal("UNAUTHORIZED", error.Code);
        Assert.Equal(0, calls);
    }

    [Fact]
    public async Task Submission_timeout_is_marked_unknown_and_not_presented_as_failure()
    {
        var store = new MemoryTokenStore();
        await store.SaveAsync("token");
        var handler = new StubHandler(async (_, ct) => { await Task.Delay(Timeout.InfiniteTimeSpan, ct); return Json(HttpStatusCode.OK, "{}"); });
        using var client = new PicPilotApiClient(new Uri("https://example.com"), store, handler);

        using var cts = new CancellationTokenSource(TimeSpan.FromMilliseconds(30));
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => client.CreateCommerceAsync(new { platform = "test" }, "task-one", cts.Token));
    }

    [Fact]
    public async Task Single_flight_rejects_same_operation_before_second_provider_call()
    {
        var store = new MemoryTokenStore();
        await store.SaveAsync("token");
        var gate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var calls = 0;
        var handler = new StubHandler(async (_, ct) =>
        {
            Interlocked.Increment(ref calls);
            await gate.Task.WaitAsync(ct);
            return Json(HttpStatusCode.OK, """{"success":true,"code":"OK","message":"ok","request_id":"r","data":{}}""");
        });
        using var client = new PicPilotApiClient(new Uri("https://example.com"), store, handler);

        var first = client.CreateCommerceAsync(new { platform = "pdd" }, "same-operation");
        await Task.Delay(30);
        var error = await Assert.ThrowsAsync<ApiException>(() => client.CreateCommerceAsync(new { platform = "pdd" }, "same-operation"));
        gate.SetResult();
        await first;

        Assert.Equal("DUPLICATE_SUBMISSION", error.Code);
        Assert.Equal(1, calls);
    }

    [Theory]
    [InlineData("queued", "排队中")]
    [InlineData("submitting", "正在提交")]
    [InlineData("generating", "生成中")]
    [InlineData("completed", "已完成")]
    [InlineData("images_completed", "出图已完成")]
    [InlineData("copy_confirmed", "文案已确认")]
    [InlineData("partial_success", "部分完成")]
    [InlineData("failed", "生成失败")]
    [InlineData("timeout", "已超时")]
    public void Status_mapping_is_unambiguous(string status, string expected) => Assert.Equal(expected, StatusLabel.For(status));

    [Theory]
    [InlineData("paid", "已支付", false)]
    [InlineData("pending", "待支付", true)]
    [InlineData("failed", "支付失败", false)]
    [InlineData("cancelled", "已关闭", false)]
    public void Recharge_order_uses_payment_specific_status(string status, string expected, bool canClose)
    {
        var order = new RechargeOrder("order", "user", "plan", "channel", 99, 100, "CNY", status, "", "2026-08-12T09:30:45+08:00");

        Assert.Equal(expected, order.StatusText);
        Assert.Equal(canClose, order.CanClose);
    }

    [Fact]
    public void Display_time_is_compact_and_user_facing()
    {
        var formatted = DisplayFormat.LocalDateTime("2026-08-12T09:30:45+08:00");

        Assert.DoesNotContain('T', formatted);
        Assert.DoesNotContain("+08:00", formatted);
        Assert.Matches(@"^(\d{2}-\d{2}|\d{4}-\d{2}-\d{2}) \d{2}:\d{2}$", formatted);
    }

    [Fact]
    public void Production_base_url_rejects_cleartext()
    {
        Assert.Throws<ArgumentException>(() => new PicPilotApiClient(new Uri("http://example.com"), new MemoryTokenStore()));
        using var staging = new PicPilotApiClient(new Uri("http://127.0.0.1:13000"), new MemoryTokenStore(), new StubHandler((_, _) => Json(HttpStatusCode.OK, "{}")));
        Assert.Equal("127.0.0.1", staging.BaseUri.Host);
    }

    [Fact]
    public async Task Listing_project_and_copy_use_the_native_web_contract()
    {
        var store = new MemoryTokenStore();
        await store.SaveAsync("token");
        var calls = new List<string>();
        var handler = new StubHandler(async (request, _) =>
        {
            calls.Add(request.RequestUri!.AbsolutePath);
            if (request.RequestUri.AbsolutePath == "/api/projects")
            {
                using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync());
                Assert.Equal("Umbrella", body.RootElement.GetProperty("product_name").GetString());
                Assert.True(body.RootElement.GetProperty("include_aplus").GetBoolean());
                return Json(HttpStatusCode.OK, ProjectEnvelope("project-1"));
            }

            Assert.Equal("/api/projects/project-1/generate-copy", request.RequestUri.AbsolutePath);
            return Json(HttpStatusCode.OK, """
                {"success":true,"code":"OK","message":"ok","request_id":"r","data":{"project_id":"project-1","status":"copy_generated","generated_copy":{"title":"Portable umbrella"}}}
                """);
        });
        using var client = new PicPilotApiClient(new Uri("https://example.com"), store, handler);

        var project = await client.CreateListingProjectAsync(new { product_name = "Umbrella", include_aplus = true }, "listing-fixture");
        var copy = await client.GenerateListingCopyAsync(project.Id);

        Assert.Equal(new[] { "/api/projects", "/api/projects/project-1/generate-copy" }, calls);
        Assert.Equal("Portable umbrella", copy.GeneratedCopy.GetProperty("title").GetString());
    }

    [Fact]
    public async Task Confirmed_listing_facts_are_wrapped_once()
    {
        var store = new MemoryTokenStore();
        await store.SaveAsync("token");
        var handler = new StubHandler(async (request, _) =>
        {
            Assert.Equal("/api/projects/project-1/confirm-facts", request.RequestUri!.AbsolutePath);
            using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync());
            Assert.Equal("red umbrella", body.RootElement.GetProperty("confirmed_facts").GetProperty("product_name").GetString());
            Assert.False(body.RootElement.GetProperty("confirmed_facts").TryGetProperty("confirmed_facts", out var nestedFacts));
            return Json(HttpStatusCode.OK, ProjectEnvelope("project-1"));
        });
        using var client = new PicPilotApiClient(new Uri("https://example.com"), store, handler);

        await client.ConfirmProjectFactsAsync("project-1", new { product_name = "red umbrella" });
    }

    [Fact]
    public async Task Aplus_reference_uses_reference_multipart_field()
    {
        var folder = Path.Combine(Path.GetTempPath(), "PicPilotTests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(folder);
        var image = Path.Combine(folder, "reference.png");
        await File.WriteAllBytesAsync(image, [0x89, 0x50, 0x4E, 0x47]);
        try
        {
            var store = new MemoryTokenStore();
            await store.SaveAsync("token");
            var handler = new StubHandler((request, _) =>
            {
                Assert.Equal("/api/projects/aplus-images", request.RequestUri!.AbsolutePath);
                Assert.Equal("multipart/form-data", request.Content!.Headers.ContentType!.MediaType);
                var multipart = Assert.IsType<MultipartFormDataContent>(request.Content);
                var names = multipart.Select(x => x.Headers.ContentDisposition?.Name?.Trim('"')).ToArray();
                Assert.Contains("reference", names);
                Assert.Contains("module", names);
                return Json(HttpStatusCode.OK, ProjectDetailEnvelope("aplus-1"));
            });
            using var client = new PicPilotApiClient(new Uri("https://example.com"), store, handler);

            await client.CreateAplusImageMultipartAsync(new Dictionary<string, string> { ["module"] = "核心卖点模块", ["prompt"] = "show product" }, image, "aplus-fixture");
        }
        finally
        {
            try { Directory.Delete(folder, true); } catch { }
        }
    }

    [Fact]
    public async Task Domestic_image_and_assistant_use_expected_routes_and_payloads()
    {
        var store = new MemoryTokenStore();
        await store.SaveAsync("token");
        var call = 0;
        var handler = new StubHandler(async (request, _) =>
        {
            call++;
            using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync());
            if (call == 1)
            {
                Assert.Equal("/api/projects/creative-images", request.RequestUri!.AbsolutePath);
                Assert.Equal("纸巾", body.RootElement.GetProperty("product_name").GetString());
                return Json(HttpStatusCode.OK, ProjectDetailEnvelope("domestic-1"));
            }

            Assert.Equal("/api/assistant/chat", request.RequestUri!.AbsolutePath);
            Assert.Equal("windows", body.RootElement.GetProperty("page_context").GetProperty("client").GetString());
            Assert.Equal("gpt-5.5", body.RootElement.GetProperty("model").GetString());
            Assert.False(body.RootElement.TryGetProperty("api_key", out var apiKey));
            return Json(HttpStatusCode.OK, """
                {"success":true,"code":"OK","message":"ok","request_id":"r","data":{"reply":"Use a clean main image","intent":"guide","action":"","action_label":"","action_href":"","suggested_prompt":"clean white background","confidence":0.9,"model":"gpt-5.5"}}
                """);
        });
        using var client = new PicPilotApiClient(new Uri("https://example.com"), store, handler);

        await client.CreateDomesticImageAsync(new { product_name = "纸巾", prompt = "白底主图" }, "domestic-fixture");
        var assistant = await client.ChatWithAssistantAsync([new AssistantChatMessage("user", "help")], "zh-CN", "gpt-5.5");

        Assert.Equal("clean white background", assistant.SuggestedPrompt);
    }

    [Fact]
    public async Task Free_creative_supports_multiple_reference_files_and_copy_agent_context()
    {
        var folder = Path.Combine(Path.GetTempPath(), "PicPilotTests", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(folder);
        var firstImage = Path.Combine(folder, "first.png");
        var secondImage = Path.Combine(folder, "second.webp");
        await File.WriteAllBytesAsync(firstImage, [0x89, 0x50, 0x4E, 0x47]);
        await File.WriteAllBytesAsync(secondImage, [0x52, 0x49, 0x46, 0x46]);
        try
        {
            var store = new MemoryTokenStore();
            await store.SaveAsync("token");
            var call = 0;
            var handler = new StubHandler(async (request, _) =>
            {
                call++;
                if (call == 1)
                {
                    Assert.Equal("/api/projects/creative-images", request.RequestUri!.AbsolutePath);
                    var multipart = Assert.IsType<MultipartFormDataContent>(request.Content);
                    Assert.Equal(2, multipart.Count(part => part.Headers.ContentDisposition?.Name?.Trim('"') == "references"));
                    Assert.Contains(multipart, part => part.Headers.ContentDisposition?.Name?.Trim('"') == "productName");
                    return Json(HttpStatusCode.OK, ProjectDetailEnvelope("free-1"));
                }

                Assert.Equal("/api/assistant/chat", request.RequestUri!.AbsolutePath);
                using var body = JsonDocument.Parse(await request.Content!.ReadAsStringAsync());
                var context = body.RootElement.GetProperty("page_context");
                Assert.Equal("copy", context.GetProperty("tab").GetString());
                Assert.Equal("copywriting_agent", context.GetProperty("task").GetString());
                return Json(HttpStatusCode.OK, """
                    {"success":true,"code":"OK","message":"ok","request_id":"r","data":{"reply":"copy","intent":"copy","action":"","action_label":"","action_href":"","suggested_prompt":"","confidence":1,"model":"gpt-5.5"}}
                    """);
            });
            using var client = new PicPilotApiClient(new Uri("https://example.com"), store, handler);

            await client.CreateCreativeImageMultipartAsync(new Dictionary<string, string>
            {
                ["productName"] = "自由创作", ["prompt"] = "commercial product image"
            }, [firstImage, secondImage], "free-fixture");
            await client.ChatWithAssistantAsync([new AssistantChatMessage("user", "write copy")], "zh-CN", "gpt-5.5",
                new { client = "windows", pathname = "/ai-create", tab = "copy", task = "copywriting_agent", logged_in = true });

            Assert.Equal(2, call);
        }
        finally
        {
            try { Directory.Delete(folder, true); } catch { }
        }
    }

    private static HttpResponseMessage Json(HttpStatusCode status, string body) => new(status)
    {
        Content = new StringContent(body, Encoding.UTF8, "application/json")
    };

    private static string ProjectEnvelope(string id) => """
        {"success":true,"code":"OK","message":"ok","request_id":"r","data":{"id":"__ID__","brand":"PicPilot","product_name":"Product","country":"US","language":"en-US","status":"draft","success_images":0,"failed_images":0,"running_images":0,"total_images":0,"credits_cost":0,"latest_failure_reason":"","latest_failed_task_id":"","cover_url":"","preview_urls":[],"created_at":"2026-08-12T00:00:00Z","updated_at":"2026-08-12T00:00:00Z"}}
        """.Replace("__ID__", id, StringComparison.Ordinal);

    private static string ProjectDetailEnvelope(string id) => """
        {"success":true,"code":"OK","message":"ok","request_id":"r","data":{"project":{"id":"__ID__","brand":"PicPilot","product_name":"Product","country":"CN","language":"zh-CN","status":"queued","success_images":0,"failed_images":0,"running_images":1,"total_images":1,"credits_cost":6,"latest_failure_reason":"","latest_failed_task_id":"","cover_url":"","preview_urls":[],"created_at":"2026-08-12T00:00:00Z","updated_at":"2026-08-12T00:00:00Z"},"original_info":{},"generated_copy":{},"confirmed_facts":{},"product_images":[],"image_tasks":[]}}
        """.Replace("__ID__", id, StringComparison.Ordinal);

    private sealed class StubHandler : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> _callback;
        public StubHandler(Func<HttpRequestMessage, CancellationToken, HttpResponseMessage> callback) => _callback = (request, ct) => Task.FromResult(callback(request, ct));
        public StubHandler(Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> callback) => _callback = callback;
        public HttpRequestMessage? LastRequest { get; private set; }
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            LastRequest = request;
            return _callback(request, cancellationToken);
        }
    }
}
