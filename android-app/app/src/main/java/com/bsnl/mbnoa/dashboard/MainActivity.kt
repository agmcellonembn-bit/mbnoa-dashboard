
package com.bsnl.mbnoa.dashboard

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val DASHBOARD_URL =
            "https://agmcellonembn-bit.github.io/mbnoa-dashboard/"

        // FIXED: Color.rgb() is not a compile-time constant.
        private val BG = Color.rgb(11, 15, 18)
    }

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var offlineMessage: TextView

    private val io = Executors.newSingleThreadExecutor()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.decorView.systemUiVisibility = 0

        val root = FrameLayout(this).apply {
            setBackgroundColor(BG)
        }

        webView = WebView(this).apply {
            setBackgroundColor(BG)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = true
        }

        progress = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            progressTintList =
                android.content.res.ColorStateList.valueOf(
                    Color.rgb(110, 231, 183)
                )
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    Color.TRANSPARENT
                )
        }

        offlineMessage = TextView(this).apply {
            text = "Unable to connect to the MBN OA Dashboard.\n\n" +
                    "Please check your internet connection and tap here to retry."
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            visibility = View.GONE

            setOnClickListener {
                visibility = View.GONE
                webView.visibility = View.VISIBLE
                webView.reload()
            }
        }

        root.addView(
            webView,
            FrameLayout.LayoutParams(-1, -1)
        )

        root.addView(
            progress,
            FrameLayout.LayoutParams(
                -1,
                dp(3),
                Gravity.TOP
            )
        )

        root.addView(
            offlineMessage,
            FrameLayout.LayoutParams(-1, -1)
        )

        setContentView(root)

        configureWebView()

        if (savedInstanceState == null) {
            webView.loadUrl(DASHBOARD_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun configureWebView() {

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode =
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)

            builtInZoomControls = false
            displayZoomControls = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }

        CookieManager.getInstance().setAcceptCookie(true)

        CookieManager.getInstance()
            .setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(
            DownloadBridge(),
            "AndroidDownloads"
        )

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return routeUrl(request.url)
            }

            @Deprecated("Deprecated in API")
            override fun shouldOverrideUrlLoading(
                view: WebView,
                url: String
            ): Boolean {
                return routeUrl(Uri.parse(url))
            }

            override fun onPageFinished(
                view: WebView,
                url: String
            ) {
                progress.visibility = View.GONE
                offlineMessage.visibility = View.GONE
                view.visibility = View.VISIBLE

                installBlobDownloadSupport(view)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    showOffline()
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: android.net.http.SslError
            ) {
                // Never bypass certificate errors.
                handler.cancel()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(
                view: WebView,
                newProgress: Int
            ) {
                progress.progress = newProgress

                progress.visibility =
                    if (newProgress in 1..99) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
            }

            override fun onConsoleMessage(
                consoleMessage: ConsoleMessage
            ): Boolean {
                android.util.Log.d(
                    "MBNOA-Web",
                    "${consoleMessage.message()} " +
                            "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                )

                return true
            }
        }

        webView.setDownloadListener(
            DownloadListener {
                    url,
                    userAgent,
                    contentDisposition,
                    mimeType,
                    _ ->

                try {
                    val uri = Uri.parse(url)

                    if (uri.scheme == "http" ||
                        uri.scheme == "https"
                    ) {
                        val filename = URLUtil.guessFileName(
                            url,
                            contentDisposition,
                            mimeType
                        )

                        val request = DownloadManager.Request(uri)
                            .setTitle(filename)
                            .setDescription(
                                "Downloading from MBN OA Dashboard"
                            )
                            .setMimeType(mimeType)
                            .addRequestHeader(
                                "User-Agent",
                                userAgent
                            )
                            .setNotificationVisibility(
                                DownloadManager.Request
                                    .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                            )
                            .setDestinationInExternalPublicDir(
                                Environment.DIRECTORY_DOWNLOADS,
                                filename
                            )

                        (
                            getSystemService(DOWNLOAD_SERVICE)
                                as DownloadManager
                        ).enqueue(request)

                        Toast.makeText(
                            this,
                            "Download started: $filename",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "Download failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun routeUrl(uri: Uri): Boolean {

        val scheme = uri.scheme?.lowercase()

        if (scheme == "http" || scheme == "https") {

            if (isTrustedHost(uri.host)) {
                return false
            }

            openExternal(uri)
            return true
        }

        if (scheme == "mailto" || scheme == "tel") {
            openExternal(uri)
            return true
        }

        return true
    }

    private fun isTrustedHost(host: String?): Boolean {

        val h = host?.lowercase() ?: return false

        return h == "agmcellonembn-bit.github.io" ||
                h == "script.google.com" ||
                h.endsWith(".googleusercontent.com") ||
                h == "docs.google.com" ||
                h == "drive.google.com" ||
                h == "cdnjs.cloudflare.com" ||
                h == "fonts.googleapis.com" ||
                h == "fonts.gstatic.com"
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, uri)
            )
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "No application can open this link",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showOffline() {

        runOnUiThread {
            webView.visibility = View.GONE
            progress.visibility = View.GONE
            offlineMessage.visibility = View.VISIBLE
        }
    }

    private fun installBlobDownloadSupport(view: WebView) {

        val js = """
            (function(){
              if(window.__mbnoaDownloadHook) return;
              window.__mbnoaDownloadHook=true;

              window.open=function(url){
                if(url){
                  window.location.href =
                    new URL(url,window.location.href).href;
                }
                return window;
              };

              var originalClick =
                HTMLAnchorElement.prototype.click;

              HTMLAnchorElement.prototype.click=function(){

                var a=this;
                var href=a.href||'';

                if(href.indexOf('blob:')===0){

                  fetch(href)
                    .then(function(r){return r.blob();})
                    .then(function(blob){

                      var reader=new FileReader();

                      reader.onloadend=function(){

                        try{
                          AndroidDownloads.saveBase64(
                            String(reader.result).split(',')[1],
                            a.download||'MBN-OA-export',
                            blob.type||'application/octet-stream'
                          );
                        }
                        catch(e){
                          console.error(
                            'Android download bridge:',e
                          );
                        }
                      };

                      reader.readAsDataURL(blob);
                    })
                    .catch(function(e){
                      console.error(
                        'Blob download failed:',e
                      );
                    });

                  return;
                }

                return originalClick.apply(this,arguments);
              };

              var originalRevoke=URL.revokeObjectURL;

              URL.revokeObjectURL=function(url){

                if(String(url).indexOf('blob:')===0){

                  setTimeout(function(){
                    originalRevoke.call(URL,url);
                  },10000);

                } else {
                  originalRevoke.call(URL,url);
                }
              };
            })();
        """.trimIndent()

        view.evaluateJavascript(js, null)
    }

    private inner class DownloadBridge {

        @JavascriptInterface
        fun saveBase64(
            base64: String,
            suggestedName: String,
            mimeType: String
        ) {

            io.execute {

                try {
                    val bytes = Base64.decode(
                        base64,
                        Base64.DEFAULT
                    )

                    val safeName = suggestedName
                        .substringAfterLast('/')
                        .substringAfterLast('\\')
                        .replace(
                            Regex("[^A-Za-z0-9._ -]"),
                            "_"
                        )
                        .take(100)
                        .ifBlank {
                            "MBN-OA-export"
                        }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                        val values = ContentValues().apply {

                            put(
                                MediaStore.Downloads.DISPLAY_NAME,
                                safeName
                            )

                            put(
                                MediaStore.Downloads.MIME_TYPE,
                                mimeType.ifBlank {
                                    "application/octet-stream"
                                }
                            )

                            put(
                                MediaStore.Downloads.RELATIVE_PATH,
                                Environment.DIRECTORY_DOWNLOADS +
                                        "/MBN OA Dashboard"
                            )

                            put(
                                MediaStore.Downloads.IS_PENDING,
                                1
                            )
                        }

                        val resolver = contentResolver

                        val item = resolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            values
                        ) ?: throw IllegalStateException(
                            "Could not create download file"
                        )

                        resolver.openOutputStream(item)?.use {
                            it.write(bytes)
                        } ?: throw IllegalStateException(
                            "Could not write download file"
                        )

                        values.clear()

                        values.put(
                            MediaStore.Downloads.IS_PENDING,
                            0
                        )

                        resolver.update(
                            item,
                            values,
                            null,
                            null
                        )

                    } else {

                        val dir =
                            getExternalFilesDir(
                                Environment.DIRECTORY_DOWNLOADS
                            ) ?: filesDir

                        dir.mkdirs()

                        File(dir, safeName).writeBytes(bytes)
                    }

                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Saved to Downloads: $safeName",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                } catch (e: Exception) {

                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "Export failed: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density
        ).toInt()
    }

    @Deprecated("Deprecated in API")
    override fun onBackPressed() {

        if (::webView.isInitialized &&
            webView.canGoBack()
        ) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {

        webView.saveState(outState)

        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {

        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.removeJavascriptInterface(
                "AndroidDownloads"
            )
            webView.destroy()
        }

        io.shutdown()

        super.onDestroy()
    }
}
