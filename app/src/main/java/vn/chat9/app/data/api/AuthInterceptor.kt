package vn.chat9.app.data.api

import okhttp3.Interceptor
import okhttp3.Response
import vn.chat9.app.data.local.TokenManager

class AuthInterceptor(
    private val tokenManager: TokenManager,
    private val onUnauthorized: () -> Unit
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
        val hadToken = tokenManager.accessToken != null
        tokenManager.accessToken?.let {
            request.addHeader("Authorization", "Bearer $it")
        }
        val response = chain.proceed(request.build())
        if (response.code == 401 && hadToken) {
            onUnauthorized()
        }
        return response
    }
}
