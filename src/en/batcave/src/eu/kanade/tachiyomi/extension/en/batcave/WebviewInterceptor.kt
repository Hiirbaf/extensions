package eu.kanade.tachiyomi.extension.en.batcave

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Interceptor
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebviewInterceptor(private val baseUrl: String) : Interceptor {

    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val origRes = chain.proceed(request)

        if (origRes.code !in listOf(403, 503)) return origRes
        origRes.close()

        resolveInWebview()

        val response = chain.proceed(request)
        if (response.code in listOf(403, 503)) {
            response.close()
            throw IOException("Abre el WebView manualmente para resolver el captcha")
        }
        return response
    }

    private fun resolveInWebview() {
        val latch = CountDownLatch(1)
        var webView: WebView? = null

        handler.post {
            val webview = WebView(context)
            webView = webview
            with(webview.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
            }

            webview.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: ""
                    if (url.contains(baseUrl) &&
                        !url.contains("cdn-cgi") &&
                        !url.contains("challenge")
                    ) {
                        latch.countDown()
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webview.loadUrl("$baseUrl/")
        }

        latch.await(20, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }
}
