package jp.feb19.kotlin_sample

/**
 * Created by feb19 on 2017/04/22.
 */

import com.github.scribejava.core.builder.api.DefaultApi20

class MastodonApi protected constructor(private val domain: String) : DefaultApi20() {

    override fun getAccessTokenEndpoint(): String {
        return String.format("https://%s/oauth/token", domain)
    }

    override fun getAuthorizationBaseUrl(): String {
        return String.format("https://%s/oauth/authorize", domain)
    }

    companion object {

        fun instance(domain: String): MastodonApi {
            return MastodonApi(domain)
        }
    }
}
