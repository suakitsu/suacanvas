use tauri::{Manager, WebviewUrl, WebviewWindowBuilder};
use url::Url;

const PIC_PILOT_URL: &str = "https://suakitsu.com/workspace?client=windows";

fn trusted_in_app_url(url: &Url) -> bool {
    if url.scheme() == "tauri" || url.as_str() == "about:blank" {
        return true;
    }
    if url.scheme() != "https" {
        return false;
    }
    match url.host_str().map(str::to_ascii_lowercase) {
        Some(host) if host == "suakitsu.com" || host.ends_with(".suakitsu.com") => true,
        Some(host) if host == "github.com" => true,
        _ => false,
    }
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_window_state::Builder::default().build())
        .plugin(tauri_plugin_single_instance::init(|app, _, _| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.show();
                let _ = window.unminimize();
                let _ = window.set_focus();
            }
        }))
        .setup(|app| {
            let url = PIC_PILOT_URL.parse::<Url>().expect("valid PicPilot URL");
            WebviewWindowBuilder::new(app, "main", WebviewUrl::External(url))
                .title("PicPilot")
                .inner_size(1440.0, 900.0)
                .min_inner_size(1024.0, 720.0)
                .resizable(true)
                .center()
                .on_navigation(|target| {
                    if trusted_in_app_url(target) {
                        true
                    } else {
                        if matches!(target.scheme(), "https" | "mailto" | "tel") {
                            let _ = open::that_detached(target.as_str());
                        }
                        false
                    }
                })
                .build()?;
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running PicPilot");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_trusted_https_hosts_remain_in_app() {
        assert!(trusted_in_app_url(&Url::parse("https://suakitsu.com/workspace").unwrap()));
        assert!(trusted_in_app_url(&Url::parse("https://api.suakitsu.com/api/public/ping").unwrap()));
        assert!(trusted_in_app_url(&Url::parse("https://github.com/login/oauth/authorize").unwrap()));
        assert!(!trusted_in_app_url(&Url::parse("http://suakitsu.com/workspace").unwrap()));
        assert!(!trusted_in_app_url(&Url::parse("https://suakitsu.com.example.org/workspace").unwrap()));
    }
}
