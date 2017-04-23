package jp.feb19.kotlin_sample

import android.app.Dialog
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.util.Pair
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.github.scribejava.core.builder.ServiceBuilder
import com.github.scribejava.core.model.OAuth2AccessToken
import com.github.scribejava.core.model.OAuthRequest
import com.github.scribejava.core.model.Verb
import com.github.scribejava.core.oauth.OAuth20Service
import org.json.JSONException
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.net.URLEncoder
import java.util.*


class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val loginBtn = findViewById(R.id.button) as Button
        loginBtn.setOnClickListener( { v ->
            val instanceDomainInput = findViewById(R.id.editText) as EditText
            val instanceDomain = instanceDomainInput.text.toString()
            Log.d("Kotlin Sample", instanceDomain)
            var url = String.format("https://%s/api/v1/accounts/verify_credentials", instanceDomain)
            Log.d("Kotlin Sample", url)

            LoginTask().execute(instanceDomain)

        })
    }

    @Throws(UnsupportedEncodingException::class)
    private fun getQuery(params: List<Pair<String, String>>): String {
        val result = StringBuilder()
        var first = true

        for (pair in params) {
            if (first)
                first = false
            else
                result.append("&")

            result.append(URLEncoder.encode(pair.first, "UTF-8"))
            result.append("=")
            result.append(URLEncoder.encode(pair.second, "UTF-8"))
        }

        return result.toString()
    }

    internal inner class LoginTask : AsyncTask<String, Void, OAuth20Service>() {

        override fun doInBackground(vararg urls: String): OAuth20Service {
            val instanceDomain = urls[0]

            Log.d("LoginTask", "instanceDomain: " + instanceDomain)

            var pref = getSharedPreferences("MastodonApiExample", MODE_PRIVATE)
            var clientId = pref.getString(String.format("client_id_for_%s", instanceDomain), null)
            var clientSecret = pref.getString(String.format("client_secret_for_%s", instanceDomain), null)

            if (clientId != null) {
                Log.d("LoginTask", "client id saved: " + clientId)
            }
            if (clientSecret != null) {
                Log.d("LoginTask", "client secret saved: " + clientSecret)
            }
            if (clientId == null || clientSecret == null) {
                Log.d("LoginTask", "Going to fetch new client id/secret")

                try {
                    val url = URL(String.format("https://%s/api/v1/apps", instanceDomain))
                    val urlConnection = url.openConnection() as HttpURLConnection

                    Log.d("LoginTask", "url: " + url)

                    urlConnection.requestMethod = "POST"

                    val params = ArrayList<Pair<String, String>>()
                    params.add(Pair("scopes", "read write follow"))
                    params.add(Pair("client_name", "Mastodon API Example"))
                    params.add(Pair("redirect_uris", "http://localhost"))

                    val os = urlConnection.outputStream
                    val writer = BufferedWriter(OutputStreamWriter(os, "UTF-8"))
                    writer.write(getQuery(params))
                    writer.flush()
                    writer.close()
                    os.close()

                    urlConnection.connect()

                    try {
                        val br = BufferedReader(InputStreamReader(urlConnection.inputStream))
                        val sb = StringBuilder()
                        var line: String

                        br.use {
                            it.lineSequence()
                                    .filter(String::isNotBlank)
                                    .forEach { sb.append(it + "\n") }
                        }

                        br.close()
                        val json = JSONObject(sb.toString())
                        clientId = json.getString("client_id")
                        clientSecret = json.getString("client_secret")

                        Log.d("LoginTask", "clientId: " + clientId)
                        Log.d("LoginTask", "clientSecret: " + clientSecret)

                        val edit = pref.edit()
                        edit.putString(String.format("client_id_for_%s", instanceDomain), clientId)
                        edit.putString(String.format("client_secret_for_%s", instanceDomain), clientSecret)
                        edit.commit()
                    } catch (e: JSONException) {
                        e.printStackTrace()
                    } finally {
                        urlConnection.disconnect()
                    }
                } catch (e: MalformedURLException) {
                    e.printStackTrace()
                } catch (e: IOException) {
                    e.printStackTrace()
                }

            }

            return ServiceBuilder()
                    .apiKey(clientId)
                    .apiSecret(clientSecret)
                    .callback("http://localhost")
                    .scope("read write follow")
                    .build(MastodonApi.instance(instanceDomain))
        }

        override fun onPostExecute(service: OAuth20Service) {
            // OAuth2 flow
            val authUrl = service.authorizationUrl
            Log.d("LoginTask", "authUrl: " + authUrl)

            var authDialog = Dialog(this@MainActivity)
            authDialog.setContentView(R.layout.auth_dialog)

            val web = authDialog.findViewById(R.id.webView) as WebView
            web.settings.javaScriptEnabled = true
            web.setWebViewClient(object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)

                    Log.d("LoginTask", "URL loaded: " + url)

                    if (url.contains("?code=")) {
                        val uri = Uri.parse(url)
                        val authCode = uri.getQueryParameter("code")
                        Log.d("LoginTask", "Auth code is: " + authCode)
                        GetAccessTokenTask().execute(service, authCode)
                        authDialog.dismiss()
                    }
                }
            })

            web.loadUrl(authUrl)

            authDialog.show()
            authDialog.setTitle("Authorize")
            authDialog.setCancelable(true)
        }
    }

    internal inner class GetAccessTokenTask : AsyncTask<Any, Void, OAuth20Service>() {

        override fun doInBackground(vararg params: Any): OAuth20Service {
            val service = params[0] as OAuth20Service
            val authCode = params[1] as String
            val domain = Uri.parse(service.authorizationUrl).host

            try {
                val token = service.getAccessToken(authCode)
                var pref = getSharedPreferences("MastodonApiExample", MODE_PRIVATE)
                val edit = pref.edit()
                edit.putString(String.format("access_token_for_%s", domain), token.accessToken)
                edit.commit()

                Log.d("GetAccessTokenTask", "Access token for " + domain + ": " + token.accessToken)
            } catch (e: IOException) {
                e.printStackTrace()
            }

            return service
        }

        override fun onPostExecute(service: OAuth20Service) {
            LoadProfileTask().execute(service)
            PostStatusTask().execute(service)
        }
    }

    internal inner class LoadProfileTask : AsyncTask<OAuth20Service, Void, String>() {

        override fun doInBackground(vararg params: OAuth20Service): String? {
            val service = params[0]
            val domain = Uri.parse(service.authorizationUrl).host
            Log.d("LoadProfileTask", "domain: " + domain)
            var pref = getSharedPreferences("MastodonApiExample", MODE_PRIVATE)
            val token = OAuth2AccessToken(pref.getString(String.format("access_token_for_%s", domain), null))
            Log.d("LoadProfileTask", "token: " + token)

            val request = OAuthRequest(Verb.GET, String.format("https://%s/api/v1/accounts/verify_credentials", domain), service)
            service.signRequest(token, request)
            val response = request.send()

            try {
                return response.body
            } catch (e: IOException) {
                e.printStackTrace()
            }

            return null
        }

        override fun onPostExecute(response: String) {
            val debug = findViewById(R.id.textView) as TextView
            debug.text = response
        }
    }

    internal inner class PostStatusTask : AsyncTask<OAuth20Service, Void, String>() {

        override fun doInBackground(vararg params: OAuth20Service): String? {
            val service = params[0]
            val domain = Uri.parse(service.authorizationUrl).host
            Log.d("PostStatusTask", "domain: " + domain)
            var pref = getSharedPreferences("MastodonApiExample", MODE_PRIVATE)
            val token = OAuth2AccessToken(pref.getString(String.format("access_token_for_%s", domain), null))

            Log.d("PostStatusTask", "token: " + token)
            val request = OAuthRequest(Verb.POST, String.format("https://%s/api/v1/statuses", domain), service)
            request.addBodyParameter("status", "test")
            service.signRequest(token, request)

            val response = request.send()

            try {
                return response.body
            } catch (e: IOException) {
                e.printStackTrace()
            }

            return null
        }

        override fun onPostExecute(response: String) {
            val debug = findViewById(R.id.textView) as TextView
            debug.text = response
        }
    }
}
