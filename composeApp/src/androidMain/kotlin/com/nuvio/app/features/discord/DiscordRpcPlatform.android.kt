package com.nuvio.app.features.discord

import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets

private const val JS_SNIPPET =
    "javascript:(function()%7Bvar%20i%3Ddocument.createElement('iframe')%3Bdocument.body.appendChild(i)%3Balert(i.contentWindow.localStorage.token.slice(1,-1))%7D)()"
private const val MOTOROLA = "motorola"
private const val SAMSUNG_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; SM-S921U; Build/UP1A.231005.007) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.363"

internal actual object DiscordRpcPlatform {
    actual val isSupported: Boolean = true

    actual fun clearLoginSession() {
        Handler(Looper.getMainLooper()).post {
            runCatching { CookieManager.getInstance().removeAllCookies(null) }
            runCatching { CookieManager.getInstance().flush() }
            runCatching { WebStorage.getInstance().deleteAllData() }
        }
    }
}

internal actual fun createDiscordGatewayHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(WebSockets)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal actual fun DiscordLoginWebView(
    modifier: Modifier,
    onLoginCompleted: (String) -> Unit,
) {
    val currentOnLoginCompleted = rememberUpdatedState(onLoginCompleted)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                var tokenRequested = false
                fun requestToken(webView: WebView, url: String?) {
                    if (tokenRequested || url == null || !url.endsWith("/app")) return
                    tokenRequested = true
                    webView.loadUrl(JS_SNIPPET)
                    webView.visibility = View.GONE
                }
                webViewClient = object : WebViewClient() {
                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(
                        webView: WebView,
                        url: String,
                    ): Boolean {
                        stopLoading()
                        requestToken(webView, url)
                        return false
                    }

                    override fun doUpdateVisitedHistory(
                        view: WebView,
                        url: String?,
                        isReload: Boolean,
                    ) {
                        super.doUpdateVisitedHistory(view, url, isReload)
                        requestToken(view, url)
                    }
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true

                if (Build.MANUFACTURER.equals(MOTOROLA, ignoreCase = true)) {
                    settings.userAgentString = SAMSUNG_USER_AGENT
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onJsAlert(
                        view: WebView,
                        url: String,
                        message: String,
                        result: JsResult,
                    ): Boolean {
                        result.confirm()
                        visibility = View.GONE
                        currentOnLoginCompleted.value(message)
                        return true
                    }
                }
                loadUrl(DiscordRpcConfig.LOGIN_URL)
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
        },
    )
}
